package org.zalando.nakadi.controller;

import com.codahale.metrics.MetricRegistry;
import org.apache.avro.specific.SpecificRecord;
import org.json.JSONException;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.zalando.nakadi.EventPublishingController;
import org.zalando.nakadi.EventPublishingExceptionHandler;
import org.zalando.nakadi.PublishingResultConverter;
import org.zalando.nakadi.cache.EventTypeCache;
import org.zalando.nakadi.config.SecuritySettings;
import org.zalando.nakadi.controller.advice.NakadiProblemExceptionHandler;
import org.zalando.nakadi.domain.BatchItemResponse;
import org.zalando.nakadi.domain.EventPublishResult;
import org.zalando.nakadi.domain.EventPublishingStatus;
import org.zalando.nakadi.domain.EventPublishingStep;
import org.zalando.nakadi.exceptions.runtime.EventTypeTimeoutException;
import org.zalando.nakadi.exceptions.runtime.InternalNakadiException;
import org.zalando.nakadi.exceptions.runtime.NoSuchEventTypeException;
import org.zalando.nakadi.kpi.event.NakadiBatchPublished;
import org.zalando.nakadi.mapper.NakadiRecordMapper;
import org.zalando.nakadi.metrics.EventTypeMetricRegistry;
import org.zalando.nakadi.metrics.EventTypeMetrics;
import org.zalando.nakadi.plugin.api.authz.AuthorizationService;
import org.zalando.nakadi.security.ClientResolver;
import org.zalando.nakadi.service.AuthorizationValidator;
import org.zalando.nakadi.service.BlacklistService;
import org.zalando.nakadi.service.publishing.BinaryEventPublisher;
import org.zalando.nakadi.service.publishing.EventPublisher;
import org.zalando.nakadi.service.publishing.NakadiKpiPublisher;
import org.zalando.nakadi.utils.TestUtils;
import org.zalando.problem.Problem;
import org.zalando.problem.Status;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.junit.Assert.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;
import static org.zalando.nakadi.config.SecuritySettings.AuthMode.OFF;
import static org.zalando.nakadi.domain.EventPublishingStatus.ABORTED;
import static org.zalando.nakadi.domain.EventPublishingStatus.FAILED;
import static org.zalando.nakadi.domain.EventPublishingStatus.SUBMITTED;
import static org.zalando.nakadi.domain.EventPublishingStep.PARTITIONING;
import static org.zalando.nakadi.domain.EventPublishingStep.PUBLISHING;
import static org.zalando.nakadi.domain.EventPublishingStep.VALIDATING;

@RunWith(MockitoJUnitRunner.class)
public class EventPublishingControllerTest {

    public static final String TOPIC = "my-topic";
    private static final String EVENT_BATCH = "[{\"payload\": \"My Event Payload\"}]";
    private static final String X_CONSUMER_TAG = "X-CONSUMER-TAG";

    private MetricRegistry metricRegistry;
    private EventPublisher publisher;
    private SecuritySettings settings;

    private MockMvc mockMvc;
    private EventTypeMetricRegistry eventTypeMetricRegistry;
    private NakadiKpiPublisher kpiPublisher;
    private BlacklistService blacklistService;
    private AuthorizationService authorizationService;

    @Captor
    private ArgumentCaptor<Supplier<SpecificRecord>> kpiEventCaptor;

    @Before
    public void setUp() {
        metricRegistry = new MetricRegistry();
        publisher = Mockito.mock(EventPublisher.class);
        eventTypeMetricRegistry = new EventTypeMetricRegistry(metricRegistry);
        kpiPublisher = Mockito.mock(NakadiKpiPublisher.class);
        settings = Mockito.mock(SecuritySettings.class);
        authorizationService = Mockito.mock(AuthorizationService.class);
        Mockito.when(settings.getAuthMode()).thenReturn(OFF);

        blacklistService = Mockito.mock(BlacklistService.class);
        Mockito.when(blacklistService.isProductionBlocked(any(), any())).thenReturn(false);

        final EventPublishingController controller =
                new EventPublishingController(publisher, Mockito.mock(BinaryEventPublisher.class),
                        eventTypeMetricRegistry, blacklistService, kpiPublisher,
                        Mockito.mock(NakadiRecordMapper.class), Mockito.mock(PublishingResultConverter.class),
                        Mockito.mock(EventTypeCache.class), Mockito.mock(AuthorizationValidator.class));

        mockMvc = standaloneSetup(controller)
                .setMessageConverters(new StringHttpMessageConverter(), TestUtils.JACKSON_2_HTTP_MESSAGE_CONVERTER)
                .setCustomArgumentResolvers(new ClientResolver(settings, authorizationService))
                .setControllerAdvice(new NakadiProblemExceptionHandler(), new EventPublishingExceptionHandler())
                .build();
    }

    @Test
    public void whenResultIsSubmittedThen200() throws Exception {
        final EventPublishResult result = new EventPublishResult(SUBMITTED, null, submittedResponses(1));

        Mockito
                .doReturn(result)
                .when(publisher)
                .publish(any(String.class), eq(TOPIC), any());

        postBatch(TOPIC, EVENT_BATCH, null)
                .andExpect(status().isOk())
                .andExpect(content().string(""));
    }

    @Test
    public void whenInvalidPostBodyThen400() throws Exception {

        Mockito.doThrow(new JSONException("Error"))
                .when(publisher)
                .publish(any(String.class), eq(TOPIC), any());

        postBatch(TOPIC, "invalid json array", null).andExpect(status().isBadRequest());
    }

    @Test
    public void whenEventPublishTimeoutThen503() throws Exception {
        Mockito.when(publisher.publish(any(), any(), any())).thenThrow(new EventTypeTimeoutException(""));

        postBatch(TOPIC, EVENT_BATCH, null)
                .andExpect(content().contentType("application/problem+json"))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    public void whenResultIsAbortedThen422() throws Exception {
        final EventPublishResult result = new EventPublishResult(ABORTED, PARTITIONING, responses());

        Mockito
                .doReturn(result)
                .when(publisher)
                .publish(any(String.class), eq(TOPIC), any());

        postBatch(TOPIC, EVENT_BATCH, null)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().string(TestUtils.JSON_TEST_HELPER.matchesObject(responses())));
    }

    @Test
    public void whenResultIsAbortedThen207() throws Exception {
        final EventPublishResult result = new EventPublishResult(FAILED, PUBLISHING, responses());

        Mockito
                .doReturn(result)
                .when(publisher)
                .publish(any(String.class), eq(TOPIC), any());

        postBatch(TOPIC, EVENT_BATCH, null)
                .andExpect(status().isMultiStatus())
                .andExpect(content().string(TestUtils.JSON_TEST_HELPER.matchesObject(responses())));
    }

    @Test
    public void whenEventTypeNotFoundThen404() throws Exception {
        Mockito
                .doThrow(new NoSuchEventTypeException("topic not found"))
                .when(publisher)
                .publish(any(String.class), eq(TOPIC), any());

        postBatch(TOPIC, EVENT_BATCH, null)
                .andExpect(content().contentType("application/problem+json"))
                .andExpect(status().isNotFound());
    }

    @Test
    public void whenConsumerTagDuplicateThen422() throws Exception {
        final var response = Problem.builder().
                withStatus(Status.UNPROCESSABLE_ENTITY).
                withTitle(Status.UNPROCESSABLE_ENTITY.getReasonPhrase()).
                withDetail("duplicate header tag: CONSUMER_SUBSCRIPTION_ID").build();

        postBatch(TOPIC, EVENT_BATCH, "consumer_subscription_id=16120729-4a57-4607-ad3a-d526a4590e75,  " +
                "consumer_subscription_id = 16120729-4a57-4607-ad3a-d526a4590e76")
                .andExpect(content().contentType("application/problem+json"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().string(TestUtils.JSON_TEST_HELPER.matchesObject(response)));
    }

    @Test
    public void whenConsumerTagEmptyThen422() throws Exception {
        final var response = Problem.builder().
                withStatus(Status.UNPROCESSABLE_ENTITY).
                withTitle(Status.UNPROCESSABLE_ENTITY.getReasonPhrase()).
                withDetail("X-CONSUMER-TAG: is empty!").build();

        postBatch(TOPIC, EVENT_BATCH, "")
                .andExpect(content().contentType("application/problem+json"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().string(TestUtils.JSON_TEST_HELPER.matchesObject(response)));
    }

    @Test
    public void whenConsumerTagSubscriptionIdIsNotUUIDThen422() throws Exception {
        final var response = Problem.builder().
                withStatus(Status.UNPROCESSABLE_ENTITY).
                withTitle(Status.UNPROCESSABLE_ENTITY.getReasonPhrase()).
                withDetail("header tag value: 123f is not an UUID").build();

        postBatch(TOPIC, EVENT_BATCH, "consumer_subscription_id=123f")
                .andExpect(content().contentType("application/problem+json"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().string(TestUtils.JSON_TEST_HELPER.matchesObject(response)));
    }

    @Test
    public void whenConsumerTagHasInvalidLengthThen422() throws Exception {
        final var response = Problem.builder().
                withStatus(Status.UNPROCESSABLE_ENTITY).
                withTitle(Status.UNPROCESSABLE_ENTITY.getReasonPhrase()).
                withDetail("header tag parameter is imbalanced, expected: 2 but provided 1").build();

        postBatch(TOPIC, EVENT_BATCH, "consumer_subscription_id=,")
                .andExpect(content().contentType("application/problem+json"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().string(TestUtils.JSON_TEST_HELPER.matchesObject(response)));
    }

    @Test
    public void publishedEventsAreReportedPerEventType() throws Exception {
        final EventPublishResult success = new EventPublishResult(SUBMITTED, null, submittedResponses(3));
        Mockito
                .doReturn(success)
                .doReturn(success)
                .doThrow(InternalNakadiException.class)
                .when(publisher)
                .publish(any(), any(), any());

        postBatch(TOPIC, EVENT_BATCH, null);
        postBatch(TOPIC, EVENT_BATCH, null);
        postBatch(TOPIC, EVENT_BATCH, null);

        final EventTypeMetrics eventTypeMetrics = eventTypeMetricRegistry.metricsFor(TOPIC);

        assertThat(eventTypeMetrics.getResponseCount(200), equalTo(2L));
        assertThat(eventTypeMetrics.getResponseCount(500), equalTo(1L));
    }

    @Test
    public void publishedEventsKPIReported() throws Exception {
        final EventPublishResult success = new EventPublishResult(SUBMITTED, null, submittedResponses(3));
        Mockito
                .doReturn(success)
                .doReturn(success)
                .doThrow(InternalNakadiException.class)
                .when(publisher)
                .publish(any(), any(), any());

        Mockito.when(kpiPublisher.hash(any())).thenReturn("hashed-application-name");

        postBatch(TOPIC, EVENT_BATCH, null);


        Mockito.verify(kpiPublisher, Mockito.times(1)).publish(kpiEventCaptor.capture());
        final NakadiBatchPublished batchPublishedEvent = (NakadiBatchPublished) kpiEventCaptor.getValue().get();
        Assert.assertEquals("my-topic", batchPublishedEvent.getEventType());
        Assert.assertEquals("unauthenticated", batchPublishedEvent.getApp());
        Assert.assertEquals("", batchPublishedEvent.getTokenRealm());
        Assert.assertEquals(3, batchPublishedEvent.getNumberOfEvents());
        Assert.assertEquals(33, batchPublishedEvent.getBatchSize());
    }

    private List<BatchItemResponse> responses() {
        final BatchItemResponse response = new BatchItemResponse();
        response.setPublishingStatus(ABORTED);
        response.setStep(VALIDATING);

        final List<BatchItemResponse> responses = new ArrayList<>();
        responses.add(response);

        return responses;
    }

    private List<BatchItemResponse> submittedResponses(final int number) {
        return responses(number, SUBMITTED, PUBLISHING);
    }

    private List<BatchItemResponse> responses(final int number, final EventPublishingStatus status,
                                              final EventPublishingStep step) {
        final List<BatchItemResponse> responses = new ArrayList<>();
        for (int i = 0; i < number; i++) {
            final BatchItemResponse response = new BatchItemResponse();
            response.setPublishingStatus(status);
            response.setStep(step);
            responses.add(response);
        }
        return responses;
    }

    private ResultActions postBatch(final String eventType, final String batch,
                                    final String consumerTagHeader) throws Exception {
        final String url = "/event-types/" + eventType + "/events";

        final MockHttpServletRequestBuilder requestBuilder = post(url)
                .contentType(APPLICATION_JSON)
                .content(batch);
        if(consumerTagHeader!= null){
            requestBuilder.header(X_CONSUMER_TAG, consumerTagHeader);
        }
        return mockMvc.perform(requestBuilder);
    }
}
