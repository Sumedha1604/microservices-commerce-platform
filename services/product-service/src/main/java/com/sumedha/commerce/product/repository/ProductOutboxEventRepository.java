package com.sumedha.commerce.product.repository;

import com.sumedha.commerce.product.entity.ProductOutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface ProductOutboxEventRepository extends JpaRepository<ProductOutboxEvent, UUID> {

    /**
     * Claims the next publishable rows: {@code PENDING}, backoff elapsed, and no <em>earlier</em>
     * {@code PENDING} row for the same product in persisted {@code (created_at, id)} order.
     *
     * <p>The guard is scoped to one product, so a product whose earlier event is in backoff holds
     * back only its own later events, and a batch claims at most one row per product. Together
     * with the {@code productId} record key this keeps each product's events in commit order on
     * its partition. {@code for update skip locked} keeps concurrent publishers off the same row.
     */
    @Query(value = "select * from product_outbox_event o "
            + "where o.status = 'PENDING' "
            + "and (o.next_attempt_at is null or o.next_attempt_at <= now()) "
            + "and not exists ("
            + "  select 1 from product_outbox_event earlier "
            + "  where earlier.aggregate_id = o.aggregate_id "
            + "    and earlier.status = 'PENDING' "
            + "    and (earlier.created_at, earlier.id) < (o.created_at, o.id)) "
            + "order by o.created_at, o.id for update skip locked limit :batchSize", nativeQuery = true)
    List<ProductOutboxEvent> lockNextBatch(@Param("batchSize") int batchSize);
}
