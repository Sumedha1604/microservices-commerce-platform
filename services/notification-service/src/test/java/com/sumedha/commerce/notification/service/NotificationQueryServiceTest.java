package com.sumedha.commerce.notification.service;

import com.sumedha.commerce.common.core.exception.BadRequestException;
import com.sumedha.commerce.common.core.exception.ResourceNotFoundException;
import com.sumedha.commerce.common.core.pagination.PageResponse;
import com.sumedha.commerce.notification.dto.response.NotificationResponse;
import com.sumedha.commerce.notification.entity.Notification;
import com.sumedha.commerce.notification.enums.NotificationChannel;
import com.sumedha.commerce.notification.enums.NotificationStatus;
import com.sumedha.commerce.notification.enums.NotificationType;
import com.sumedha.commerce.notification.repository.NotificationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class NotificationQueryServiceTest {

    private NotificationRepository repository;
    private NotificationQueryService service;

    private final UUID orderId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        repository = mock(NotificationRepository.class);
        service = new NotificationQueryService(repository);
    }

    private Notification notification() {
        return new Notification(UUID.randomUUID(), "PaymentAuthorized", Instant.now(), orderId,
                UUID.randomUUID(), userId, NotificationChannel.INTERNAL, NotificationType.PAYMENT_AUTHORIZED,
                "subject", "message");
    }

    @Test
    void getByIdMapsTheRecord() {
        Notification stored = notification();
        when(repository.findById(stored.getId())).thenReturn(Optional.of(stored));

        NotificationResponse response = service.getById(stored.getId());

        assertEquals(stored.getId(), response.notificationId());
        assertEquals(stored.getEventId(), response.eventId());
        assertEquals(NotificationStatus.CREATED, response.status());
        assertEquals(NotificationChannel.INTERNAL, response.channel());
    }

    @Test
    void anUnknownIdIsNotFound() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.empty());

        ResourceNotFoundException notFound = assertThrows(ResourceNotFoundException.class, () -> service.getById(id));

        assertEquals("Notification not found: " + id, notFound.getMessage());
    }

    @Test
    void listPassesEveryFilterThroughNewestFirst() {
        when(repository.search(any(), any(), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(notification()), org.springframework.data.domain.PageRequest.of(2, 5), 11));

        PageResponse<NotificationResponse> page = service.list(orderId, userId, "PaymentAuthorized",
                NotificationType.PAYMENT_AUTHORIZED, NotificationStatus.CREATED, 2, 5);

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(repository).search(eq(orderId), eq(userId), eq("PaymentAuthorized"),
                eq(NotificationType.PAYMENT_AUTHORIZED), eq(NotificationStatus.CREATED), pageable.capture());
        assertEquals(2, pageable.getValue().getPageNumber());
        assertEquals(5, pageable.getValue().getPageSize());
        assertEquals(Sort.by(Sort.Order.desc("createdAt"), Sort.Order.asc("id")), pageable.getValue().getSort());

        assertEquals(1, page.getItems().size());
        assertEquals(11, page.getTotalElements());
        assertEquals(3, page.getTotalPages());
        assertEquals(false, page.isHasNext());
        assertEquals(true, page.isHasPrevious());
    }

    @Test
    void aBlankEventTypeMeansNoFilter() {
        when(repository.search(any(), any(), any(), any(), any(), any())).thenReturn(new PageImpl<>(List.of()));

        service.list(null, null, "  ", null, null, 0, 20);

        verify(repository).search(isNull(), isNull(), isNull(), isNull(), isNull(), any());
    }

    @Test
    void anUnsupportedEventTypeIsABadRequest() {
        assertThrows(BadRequestException.class,
                () -> service.list(null, null, "InventoryReleaseRequested", null, null, 0, 20));
        verifyNoInteractions(repository);
    }

    @ParameterizedTest
    @CsvSource({"-1, 20", "0, 0", "0, -5", "0, 101"})
    void anOutOfRangePageIsABadRequest(int page, int size) {
        assertThrows(BadRequestException.class, () -> service.list(null, null, null, null, null, page, size));
        assertThrows(BadRequestException.class, () -> service.listByOrder(orderId, page, size));
        verifyNoInteractions(repository);
    }

    @Test
    void theLargestAllowedPageIsAccepted() {
        when(repository.search(any(), any(), any(), any(), any(), any())).thenReturn(new PageImpl<>(List.of()));

        service.list(null, null, null, null, null, 0, NotificationQueryService.MAX_PAGE_SIZE);

        verify(repository).search(isNull(), isNull(), isNull(), isNull(), isNull(), any());
    }

    @Test
    void listByOrderFiltersOnTheOrderOnly() {
        when(repository.search(any(), any(), any(), any(), any(), any())).thenReturn(new PageImpl<>(List.of()));

        service.listByOrder(orderId, 0, 20);

        verify(repository).search(eq(orderId), isNull(), isNull(), isNull(), isNull(), any());
    }
}
