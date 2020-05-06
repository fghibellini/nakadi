package org.zalando.nakadi.service;


import com.google.common.collect.Lists;
import org.everit.json.schema.Schema;
import org.everit.json.schema.ValidationException;
import org.everit.json.schema.loader.SchemaLoader;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.json.JSONObject;
import org.zalando.nakadi.domain.CompatibilityMode;
import org.zalando.nakadi.domain.EnrichmentStrategyDescriptor;
import org.zalando.nakadi.domain.EventCategory;
import org.zalando.nakadi.domain.EventType;
import org.zalando.nakadi.domain.EventTypeBase;
import org.zalando.nakadi.domain.EventTypeSchema;
import org.zalando.nakadi.domain.SchemaChange;
import org.zalando.nakadi.domain.Version;
import org.zalando.nakadi.exceptions.runtime.InvalidEventTypeException;
import org.zalando.nakadi.validation.SchemaIncompatibility;
import org.zalando.nakadi.validation.schema.SchemaEvolutionConstraint;
import org.zalando.nakadi.validation.schema.diff.SchemaDiff;
import org.zalando.nakadi.validation.schema.ForbiddenAttributeIncompatibility;
import org.zalando.nakadi.validation.schema.SchemaEvolutionIncompatibility;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.BinaryOperator;
import java.util.stream.Collectors;

import static org.zalando.nakadi.domain.SchemaChange.Type.ADDITIONAL_ITEMS_CHANGED;
import static org.zalando.nakadi.domain.SchemaChange.Type.ADDITIONAL_PROPERTIES_CHANGED;
import static org.zalando.nakadi.domain.SchemaChange.Type.DESCRIPTION_CHANGED;
import static org.zalando.nakadi.domain.SchemaChange.Type.PROPERTIES_ADDED;
import static org.zalando.nakadi.domain.SchemaChange.Type.REQUIRED_ARRAY_EXTENDED;
import static org.zalando.nakadi.domain.SchemaChange.Type.TITLE_CHANGED;
import static org.zalando.nakadi.domain.Version.Level.MAJOR;
import static org.zalando.nakadi.domain.Version.Level.NO_CHANGES;
import static org.zalando.nakadi.domain.Version.Level.PATCH;

public class SchemaEvolutionService {

    private final List<SchemaEvolutionConstraint> schemaEvolutionConstraints;
    private final Schema metaSchema;
    private final SchemaDiff schemaDiff;
    private final Map<SchemaChange.Type, String> errorMessages;
    private static final List<SchemaChange.Type> FORWARD_TO_COMPATIBLE_ALLOWED_CHANGES = Lists.newArrayList(
            DESCRIPTION_CHANGED, TITLE_CHANGED, PROPERTIES_ADDED, REQUIRED_ARRAY_EXTENDED,
            ADDITIONAL_PROPERTIES_CHANGED, ADDITIONAL_ITEMS_CHANGED);
    private final BiFunction<SchemaChange.Type, CompatibilityMode, Version.Level> levelResolver;


    public SchemaEvolutionService(final Schema metaSchema,
                                  final List<SchemaEvolutionConstraint> schemaEvolutionConstraints,
                                  final SchemaDiff schemaDiff,
                                  final BiFunction<SchemaChange.Type, CompatibilityMode, Version.Level> levelResolver,
                                  final Map<SchemaChange.Type, String> errorMessages) {
        this.metaSchema = metaSchema;
        this.schemaEvolutionConstraints = schemaEvolutionConstraints;
        this.schemaDiff = schemaDiff;
        this.levelResolver = levelResolver;
        this.errorMessages = errorMessages;
    }

    public SchemaEvolutionService(final Schema metaSchema,
                                  final List<SchemaEvolutionConstraint> schemaEvolutionConstraints,
                                  final SchemaDiff schemaDiff,
                                  final Map<SchemaChange.Type, String> errorMessages) {
        this(metaSchema, schemaEvolutionConstraints, schemaDiff, SchemaChange.Type::getLevel, errorMessages);
    }

    public List<SchemaIncompatibility> collectIncompatibilities(final JSONObject schemaJson) {
        final List<SchemaIncompatibility> incompatibilities = new ArrayList<>();

        try {
            metaSchema.validate(schemaJson);
        } catch (final ValidationException e) {
            collectErrorMessages(e, incompatibilities);
        }

        return incompatibilities;
    }

    private void collectErrorMessages(final ValidationException e,
                                      final List<SchemaIncompatibility> incompatibilities) {
        if (e.getCausingExceptions().isEmpty()) {
            incompatibilities.add(
                    new ForbiddenAttributeIncompatibility(e.getPointerToViolation(), e.getErrorMessage()));
        } else {
            e.getCausingExceptions()
                    .forEach(causingException -> collectErrorMessages(causingException, incompatibilities));
        }
    }

    public EventType evolve(final EventType original, final EventTypeBase eventType) throws InvalidEventTypeException {
        checkEvolutionIncompatibilities(original, eventType);

        final List<SchemaChange> changes = schemaDiff.collectChanges(schema(original), schema(eventType));

        final Version.Level changeLevel = semanticOfChange(original.getSchema().getSchema(),
                eventType.getSchema().getSchema(), changes, original.getCompatibilityMode());

        if (isForwardToCompatibleUpgrade(original, eventType)) {
            validateCompatibilityModeMigration(changes);
        } else if (original.getCompatibilityMode() != CompatibilityMode.NONE) {
            validateCompatibleChanges(original, changes, changeLevel);
        }

        return bumpVersion(original, eventType, changeLevel);
    }

    private void checkEvolutionIncompatibilities(final EventType from, final EventTypeBase to)
            throws InvalidEventTypeException {
        final List<SchemaEvolutionIncompatibility> incompatibilities = schemaEvolutionConstraints.stream()
                .map(c -> c.validate(from, to))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .collect(Collectors.toList());
        if (incompatibilities.isEmpty()) {
            return;
        }
        // There is a special case of incompatibility - change of category from NONE to BUSINESS, with adding metadata.
        // Let's check that it is the case.
        // This solution is not scalable, but without huge refactoring it is the simplest one.
        final boolean categoryChanged = incompatibilities.stream()
                .anyMatch(v -> v instanceof SchemaEvolutionIncompatibility.CategoryIncompatibility);
        if (categoryChanged) {
            final boolean metadataChanged = incompatibilities.stream()
                    .anyMatch(v -> v instanceof SchemaEvolutionIncompatibility.MetadataIncompatibility);
            final int expectedIncompatibilities = 1 + (metadataChanged ? 1 : 0);
            if (incompatibilities.size() == expectedIncompatibilities) {
                // Now let's check, that it is really change from NONE to BUSINESS and some other conditions.
                if (from.getCategory() == EventCategory.UNDEFINED && to.getCategory() == EventCategory.BUSINESS) {
                    if (to.getEnrichmentStrategies().contains(EnrichmentStrategyDescriptor.METADATA_ENRICHMENT)
                            && to.getCompatibilityMode() != CompatibilityMode.COMPATIBLE) {
                        // Finally, we are allowing this step.
                        return;
                    }
                }
            }
            throw new InvalidEventTypeException("Category change is allowed only from 'undefined' to 'business'. " +
                    "'enrichment_strategies' should be properly set as well");
        }

        throw new InvalidEventTypeException(incompatibilities.stream()
                .map(SchemaEvolutionIncompatibility::getReason)
                .collect(Collectors.joining("; ")));
    }

    private boolean isForwardToCompatibleUpgrade(final EventType original, final EventTypeBase eventType) {
        return original.getCompatibilityMode() == CompatibilityMode.FORWARD
                && eventType.getCompatibilityMode() == CompatibilityMode.COMPATIBLE;
    }

    private void validateCompatibilityModeMigration(final List<SchemaChange> changes) throws InvalidEventTypeException {
        final List<SchemaChange> forbiddenChanges = changes.stream()
                .filter(change -> !FORWARD_TO_COMPATIBLE_ALLOWED_CHANGES.contains(change.getType()))
                .collect(Collectors.toList());
        if (!forbiddenChanges.isEmpty()) {
            final String errorMessage = forbiddenChanges.stream().map(this::formatErrorMessage)
                    .collect(Collectors.joining(", "));
            throw new InvalidEventTypeException("Invalid schema: " + errorMessage);
        }
    }

    private void validateCompatibleChanges(final EventType original, final List<SchemaChange> changes,
                                           final Version.Level changeLevel) throws InvalidEventTypeException {
        if ((original.getCompatibilityMode() == CompatibilityMode.COMPATIBLE
                || original.getCompatibilityMode() == CompatibilityMode.FORWARD)
                && changeLevel == MAJOR) {
            final String errorMessage = changes.stream()
                    .filter(change -> MAJOR.equals(
                            levelResolver.apply(change.getType(), original.getCompatibilityMode())))
                    .map(this::formatErrorMessage)
                    .collect(Collectors.joining(", "));
            throw new InvalidEventTypeException("Invalid schema: " + errorMessage);
        }
    }

    private String formatErrorMessage(final SchemaChange change) {
        return change.getJsonPath() + ": " + errorMessages.get(change.getType());
    }

    private Version.Level semanticOfChange(final String originalSchema, final String updatedSchema,
                                           final List<SchemaChange> changes,
                                           final CompatibilityMode compatibilityMode) {
        if (changes.isEmpty() && !originalSchema.equals(updatedSchema)) {
            return PATCH;
        } else {
            return changes.stream()
                    .map(SchemaChange::getType)
                    .map(v -> levelResolver.apply(v, compatibilityMode))
                    .reduce(NO_CHANGES, collectOverallChange());
        }
    }

    private BinaryOperator<Version.Level> collectOverallChange() {
        return (acc, change) -> {
            if (Version.Level.valueOf(change.toString()).ordinal() < Version.Level.valueOf(acc.toString()).ordinal()) {
                return change;
            } else {
                return acc;
            }
        };
    }

    private Schema schema(final EventTypeBase eventType) {
        final JSONObject schemaAsJson = new JSONObject(eventType.getSchema().getSchema());

        return SchemaLoader.load(schemaAsJson);
    }

    private EventType bumpVersion(final EventType original, final EventTypeBase eventType,
                                  final Version.Level changeLevel) {
        final DateTime now = new DateTime(DateTimeZone.UTC);
        final String newVersion = original.getSchema().getVersion().bump(changeLevel).toString();

        final EventTypeSchema schema = changeLevel == NO_CHANGES ? original.getSchema():
                new EventTypeSchema(eventType.getSchema(), newVersion, now);
        return new EventType(eventType, original.getCreatedAt(), now, schema);
    }
}
