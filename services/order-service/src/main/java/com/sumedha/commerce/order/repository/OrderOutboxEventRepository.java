package com.sumedha.commerce.order.repository;

import com.sumedha.commerce.order.entity.OrderOutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface OrderOutboxEventRepository extends JpaRepository<OrderOutboxEvent, UUID> {

    /**
     * Claims the next publishable rows. A row is publishable only when it is {@code PENDING},
     * its backoff has elapsed, and no <em>earlier</em> {@code PENDING} row exists for the same
     * {@code aggregate_id}, where "earlier" is the persisted {@code (created_at, id)} order.
     *
     * <p>The {@code not exists} guard is scoped to one aggregate, so an order whose earlier
     * event is still in backoff holds back only its own later events - every other order keeps
     * publishing. It also means a single batch claims at most one row per aggregate; the next
     * poll picks up that aggregate's successor once its predecessor is {@code PUBLISHED}.
     *
     * <p>The guard evaluates against this transaction's snapshot, so a predecessor another
     * worker has claimed but not yet committed still reads as {@code PENDING} and still blocks -
     * the conservative direction. {@code for update skip locked} keeps concurrent workers off
     * the same row, so two publishers can never claim one event.
     */
    @Query(value = "select * from order_outbox_event o "
            + "where o.status = 'PENDING' "
            + "and (o.next_attempt_at is null or o.next_attempt_at <= now()) "
            + "and not exists ("
            + "  select 1 from order_outbox_event earlier "
            + "  where earlier.aggregate_id = o.aggregate_id "
            + "    and earlier.status = 'PENDING' "
            + "    and (earlier.created_at, earlier.id) < (o.created_at, o.id)) "
            + "order by o.created_at, o.id for update skip locked limit :batchSize", nativeQuery = true)
    List<OrderOutboxEvent> lockNextBatch(@Param("batchSize") int batchSize);

    /** Operational visibility: how much compensation is still owed to Kafka. */
    long countByStatus(com.sumedha.commerce.order.enums.OutboxEventStatus status);

    List<OrderOutboxEvent> findByAggregateIdOrderByCreatedAtAscIdAsc(UUID aggregateId);
}
