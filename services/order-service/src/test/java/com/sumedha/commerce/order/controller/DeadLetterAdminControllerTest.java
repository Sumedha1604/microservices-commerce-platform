package com.sumedha.commerce.order.controller;

import com.sumedha.commerce.common.core.exception.BadRequestException;
import com.sumedha.commerce.common.core.exception.InternalServerException;
import com.sumedha.commerce.common.core.exception.ResourceNotFoundException;
import com.sumedha.commerce.common.core.pagination.PageResponse;
import com.sumedha.commerce.common.events.EventTypes;
import com.sumedha.commerce.common.events.KafkaTopics;
import com.sumedha.commerce.order.dto.response.DeadLetterEventDetailResponse;
import com.sumedha.commerce.order.dto.response.DeadLetterEventResponse;
import com.sumedha.commerce.order.dto.response.DeadLetterReplayResponse;
import com.sumedha.commerce.order.enums.DeadLetterStatus;
import com.sumedha.commerce.order.exception.GlobalExceptionHandler;
import com.sumedha.commerce.order.service.DeadLetterAdminService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DeadLetterAdminControllerTest {

    private static final String BASE = "/api/v1/admin/dlt/payment-events";

    MockMvc mvc;
    DeadLetterAdminService service;

    final UUID id = UUID.randomUUID();
    final UUID eventId = UUID.randomUUID();
    final UUID orderId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = mock(DeadLetterAdminService.class);
        mvc = MockMvcBuilders.standaloneSetup(new DeadLetterAdminController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private DeadLetterEventResponse response() {
        return new DeadLetterEventResponse(id, eventId, EventTypes.PAYMENT_AUTHORIZED, 1, orderId,
                KafkaTopics.PAYMENT_EVENTS_V1, 2, 41L,
                KafkaTopics.PAYMENT_EVENTS_V1_DLT, 1, 100L, Instant.now(),
                orderId.toString(), "NonRetryableEventException", "order is CANCELLED",
                "order-service", "00-trace-span-01", Instant.now(),
                DeadLetterStatus.NEW, null, 0, null);
    }

    // ---------- list ----------

    @Test
    void listReturnsThePageAndPassesEveryFilterThrough() throws Exception {
        when(service.list(eq(eventId), eq(EventTypes.PAYMENT_AUTHORIZED), eq(orderId), eq(2),
                eq(DeadLetterStatus.NEW), eq(1), eq(50)))
                .thenReturn(PageResponse.of(List.of(response()), 1, 50, 60));

        mvc.perform(get(BASE)
                        .param("eventId", eventId.toString())
                        .param("eventType", EventTypes.PAYMENT_AUTHORIZED)
                        .param("orderId", orderId.toString())
                        .param("partition", "2")
                        .param("status", "NEW")
                        .param("page", "1")
                        .param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.items[0].id").value(id.toString()))
                .andExpect(jsonPath("$.data.items[0].eventId").value(eventId.toString()))
                .andExpect(jsonPath("$.data.items[0].dltOffset").value(100))
                .andExpect(jsonPath("$.data.totalElements").value(60))
                // page 1 of 2 with size 50: the last page, so only a previous page exists
                .andExpect(jsonPath("$.data.totalPages").value(2))
                .andExpect(jsonPath("$.data.hasNext").value(false))
                .andExpect(jsonPath("$.data.hasPrevious").value(true));
    }

    @Test
    void listUsesBoundedDefaultsWhenNoPagingIsGiven() throws Exception {
        when(service.list(isNull(), isNull(), isNull(), isNull(), isNull(), eq(0), eq(20)))
                .thenReturn(PageResponse.of(List.of(), 0, 20, 0));

        mvc.perform(get(BASE)).andExpect(status().isOk());

        verify(service).list(null, null, null, null, null, 0, 20);
    }

    @Test
    void listNeverExposesThePayload() throws Exception {
        when(service.list(any(), any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(PageResponse.of(List.of(response()), 0, 20, 1));

        mvc.perform(get(BASE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].payload").doesNotExist());
    }

    @Test
    void anOversizedPageIsRejectedByTheService() throws Exception {
        when(service.list(any(), any(), any(), any(), any(), anyInt(), anyInt()))
                .thenThrow(new BadRequestException("size must be between 1 and 100"));

        mvc.perform(get(BASE).param("size", "1000"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("BAD_REQUEST"));
    }

    @Test
    void anUnparseableFilterValueIsABadRequest() throws Exception {
        mvc.perform(get(BASE).param("eventId", "not-a-uuid"))
                .andExpect(status().isBadRequest());

        verify(service, never()).list(any(), any(), any(), any(), any(), anyInt(), anyInt());
    }

    // ---------- detail ----------

    @Test
    void detailReturnsTheRecordWithItsPayload() throws Exception {
        when(service.getById(id)).thenReturn(
                new DeadLetterEventDetailResponse(response(), "{\"eventId\":\"x\"}"));

        mvc.perform(get(BASE + "/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.event.id").value(id.toString()))
                .andExpect(jsonPath("$.data.payload").value("{\"eventId\":\"x\"}"));
    }

    @Test
    void anUnknownIdIsNotFound() throws Exception {
        when(service.getById(id)).thenThrow(new ResourceNotFoundException("Dead-letter record not found: " + id));

        mvc.perform(get(BASE + "/" + id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("RESOURCE_NOT_FOUND"));
    }

    // ---------- replay ----------

    @Test
    void replayReturnsWhereTheRecordWasRepublishedAndItsPreservedEventId() throws Exception {
        when(service.replay(id)).thenReturn(new DeadLetterReplayResponse(id, eventId,
                KafkaTopics.PAYMENT_EVENTS_V1, orderId.toString(), 1, 77L,
                DeadLetterStatus.REPLAYED, Instant.now(), 1));

        mvc.perform(post(BASE + "/" + id + "/replay"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.replayedToTopic").value(KafkaTopics.PAYMENT_EVENTS_V1))
                .andExpect(jsonPath("$.data.eventId").value(eventId.toString()))
                .andExpect(jsonPath("$.data.status").value("REPLAYED"))
                .andExpect(jsonPath("$.data.replayCount").value(1));
    }

    @Test
    void replayOfAnUnknownRecordIsNotFound() throws Exception {
        when(service.replay(id)).thenThrow(new ResourceNotFoundException("Dead-letter record not found: " + id));

        mvc.perform(post(BASE + "/" + id + "/replay"))
                .andExpect(status().isNotFound());
    }

    @Test
    void aKafkaFailureIsReportedClearlyAndDoesNotClaimSuccess() throws Exception {
        when(service.replay(id)).thenThrow(new InternalServerException(
                "Replay of dead-letter record " + id + " was not acknowledged by Kafka; "
                        + "the record is unchanged and can be replayed again"));

        mvc.perform(post(BASE + "/" + id + "/replay"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.errorCode").value("INTERNAL_SERVER_ERROR"))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("can be replayed again")));
    }

    @Test
    void aMalformedStoredPayloadIsRejectedSafely() throws Exception {
        when(service.replay(id)).thenThrow(new BadRequestException("Dead-letter record " + id
                + " does not hold a replayable v1 payment event: Record value is not a valid v1 event envelope"));

        mvc.perform(post(BASE + "/" + id + "/replay"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("BAD_REQUEST"));
    }

    // ---------- the endpoint offers no way to choose a topic or a payload ----------

    @Test
    void aClientSuppliedTopicOrPayloadIsIgnoredEntirely() throws Exception {
        when(service.replay(id)).thenReturn(new DeadLetterReplayResponse(id, eventId,
                KafkaTopics.PAYMENT_EVENTS_V1, orderId.toString(), 0, 1L,
                DeadLetterStatus.REPLAYED, Instant.now(), 1));

        mvc.perform(post(BASE + "/" + id + "/replay")
                        .param("topic", "attacker.topic")
                        .param("originalTopic", "attacker.topic")
                        .param("payload", "{\"eventId\":\"injected\"}")
                        .contentType("application/json")
                        .content("{\"topic\":\"attacker.topic\",\"payload\":\"injected\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.replayedToTopic").value(KafkaTopics.PAYMENT_EVENTS_V1));

        // The record id is the only thing that reached the service.
        verify(service).replay(id);
        verifyNoMoreInteractions(service);
    }
}
