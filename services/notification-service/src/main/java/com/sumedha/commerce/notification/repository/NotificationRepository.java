package com.sumedha.commerce.notification.repository;

import com.sumedha.commerce.notification.entity.Notification;
import com.sumedha.commerce.notification.enums.NotificationStatus;
import com.sumedha.commerce.notification.enums.NotificationType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    /**
     * Bounded, filtered listing. Every filter is optional; a null one is ignored. The result is
     * always paged - no query path in this repository can scan the table unbounded.
     */
    @Query("""
            select n from Notification n
            where (:orderId is null or n.orderId = :orderId)
              and (:userId is null or n.userId = :userId)
              and (:eventType is null or n.eventType = :eventType)
              and (:notificationType is null or n.notificationType = :notificationType)
              and (:status is null or n.status = :status)
            """)
    Page<Notification> search(@Param("orderId") UUID orderId,
                              @Param("userId") UUID userId,
                              @Param("eventType") String eventType,
                              @Param("notificationType") NotificationType notificationType,
                              @Param("status") NotificationStatus status,
                              Pageable pageable);

    long countByEventId(UUID eventId);
}
