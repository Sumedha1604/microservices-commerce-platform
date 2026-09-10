package com.sumedha.commerce.notification.repository;

import com.sumedha.commerce.notification.entity.ProcessedEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, UUID> {

    /**
     * Claims {@code eventId} for the current transaction, or reports that it is already taken.
     *
     * <p>A guarded insert rather than "exists, then insert", so there is no window between the
     * check and the write. When two transactions claim the same id concurrently, PostgreSQL makes
     * the second wait on the first: if the first commits, the second inserts nothing and gets
     * {@code 0}; if the first rolls back, the second claims the id and gets {@code 1}. Either way
     * the answer is final and no exception has to be interpreted as "probably a duplicate".
     *
     * @return {@code 1} if this transaction claimed the event, {@code 0} if it was already claimed
     */
    @Modifying
    @Query(value = "insert into processed_event (event_id, event_type, order_id, processed_at) "
            + "values (:eventId, :eventType, :orderId, now()) "
            + "on conflict (event_id) do nothing", nativeQuery = true)
    int insertIfAbsent(@Param("eventId") UUID eventId,
                       @Param("eventType") String eventType,
                       @Param("orderId") UUID orderId);
}
