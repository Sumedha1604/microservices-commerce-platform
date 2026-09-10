package com.sumedha.commerce.order.repository;

import com.sumedha.commerce.order.entity.DeadLetterEvent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

public interface DeadLetterEventRepository extends JpaRepository<DeadLetterEvent, UUID> {

    /** Pre-check for the {@code (dlt_topic, dlt_partition, dlt_offset)} uniqueness constraint. */
    boolean existsByDltTopicAndDltPartitionAndDltOffset(String dltTopic, int dltPartition, long dltOffset);

    /**
     * Bounded, filtered listing. Every filter is optional; a null one is ignored. The result is
     * always paged - no query path in this repository can scan the table unbounded.
     */
    @Query("""
            select d from DeadLetterEvent d
            where (:eventId is null or d.eventId = :eventId)
              and (:eventType is null or d.eventType = :eventType)
              and (:orderId is null or d.orderId = :orderId)
              and (:dltPartition is null or d.dltPartition = :dltPartition)
              and (:status is null or d.status = :status)
            """)
    Page<DeadLetterEvent> search(@Param("eventId") UUID eventId,
                                 @Param("eventType") String eventType,
                                 @Param("orderId") UUID orderId,
                                 @Param("dltPartition") Integer dltPartition,
                                 @Param("status") com.sumedha.commerce.order.enums.DeadLetterStatus status,
                                 Pageable pageable);
}
