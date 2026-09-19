package com.sumedha.commerce.recommendation.messaging;

import com.sumedha.commerce.recommendation.repository.ProcessedEventRepository;
import com.sumedha.commerce.recommendation.repository.RecommendationProductRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies one product event to the recommendation projection, and records that it was handled, in
 * one transaction.
 *
 * <p>Two independent guards: the {@code processed_event} claim makes a redelivery of the same
 * {@code eventId} a no-op, and the {@code source_version} guard inside the projection statement makes
 * an older product state a no-op under any eventId - including an upsert replayed after the delete.
 * Neither is an exception, so nothing is retried or dead-lettered for being late. A failed projection
 * rolls the claim back, so the retry is processed; the Kafka offset is committed only after this
 * method returns.
 */
@Service
public class ProductProjectionProcessor {

    private static final Logger log = LoggerFactory.getLogger(ProductProjectionProcessor.class);

    public enum Outcome {
        /** The product's state was inserted or replaced. */
        UPSERTED,
        /** The product was tombstoned and can no longer be recommended or used as a source. */
        DELETED,
        /** A newer version (or the deletion) was already applied; nothing changed. */
        STALE_IGNORED,
        /** This exact eventId was already processed; nothing changed. */
        DUPLICATE
    }

    private final ProcessedEventRepository processedEvents;
    private final RecommendationProductRepository products;

    public ProductProjectionProcessor(ProcessedEventRepository processedEvents,
                                      RecommendationProductRepository products) {
        this.processedEvents = processedEvents;
        this.products = products;
    }

    @Transactional
    public Outcome process(ProductEvent event) {
        if (processedEvents.claim(event.eventId(), event.eventType(), event.productId()) == 0) {
            log.info("Product event duplicate ignored eventId={} eventType={} productId={}",
                    event.eventId(), event.eventType(), event.productId());
            return Outcome.DUPLICATE;
        }

        Outcome outcome = switch (event) {
            case ProductEvent.Upserted upserted ->
                    products.upsert(upserted.eventId(), upserted.payload()) == 1 ? Outcome.UPSERTED : Outcome.STALE_IGNORED;
            case ProductEvent.Deleted deleted ->
                    products.tombstone(deleted.eventId(), deleted.productId(), deleted.version()) == 1
                            ? Outcome.DELETED : Outcome.STALE_IGNORED;
        };

        log.info("Product event applied to recommendations eventId={} eventType={} productId={} version={} outcome={}",
                event.eventId(), event.eventType(), event.productId(), event.version(), outcome);
        return outcome;
    }
}
