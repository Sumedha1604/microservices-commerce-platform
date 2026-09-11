package com.sumedha.commerce.search.messaging;

import com.sumedha.commerce.search.repository.ProcessedEventRepository;
import com.sumedha.commerce.search.repository.ProductSearchDocumentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies one product event to the search read model, and records that it was handled, in one
 * transaction.
 *
 * <p><strong>Two independent guards.</strong> The {@code processed_event} claim makes a redelivery
 * of the same {@code eventId} a no-op. The {@code source_version} guard inside the projection
 * statement makes an <em>older product state</em> a no-op even when it arrives under a different
 * eventId - a replayed ProductUpserted after a newer update, or after the delete. Neither outcome is
 * an exception, so nothing is retried or dead-lettered for being late.
 *
 * <p><strong>One transaction.</strong> Claim and projection change commit together or not at all;
 * a failed projection rolls the claim back, so the retry (or an operator replay) is processed. The
 * Kafka offset is committed only after this method returns.
 */
@Service
public class ProductProjectionProcessor {

    private static final Logger log = LoggerFactory.getLogger(ProductProjectionProcessor.class);

    public enum Outcome {
        /** The product's searchable state was inserted or replaced. */
        UPSERTED,
        /** The product was removed from search (tombstoned). */
        DELETED,
        /** A newer version (or the deletion) was already applied; nothing changed. */
        STALE_IGNORED,
        /** This exact eventId was already processed; nothing changed. */
        DUPLICATE
    }

    private final ProcessedEventRepository processedEvents;
    private final ProductSearchDocumentRepository documents;

    public ProductProjectionProcessor(ProcessedEventRepository processedEvents,
                                      ProductSearchDocumentRepository documents) {
        this.processedEvents = processedEvents;
        this.documents = documents;
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
                    documents.upsert(upserted.eventId(), upserted.payload()) == 1 ? Outcome.UPSERTED : Outcome.STALE_IGNORED;
            case ProductEvent.Deleted deleted ->
                    documents.tombstone(deleted.eventId(), deleted.productId(), deleted.version()) == 1
                            ? Outcome.DELETED : Outcome.STALE_IGNORED;
        };

        log.info("Product event applied to search eventId={} eventType={} productId={} version={} outcome={}",
                event.eventId(), event.eventType(), event.productId(), event.version(), outcome);
        return outcome;
    }
}
