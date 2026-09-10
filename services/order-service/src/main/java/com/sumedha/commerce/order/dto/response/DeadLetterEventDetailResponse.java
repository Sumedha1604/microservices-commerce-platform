package com.sumedha.commerce.order.dto.response;

/**
 * Detail view: the listing metadata plus the original record value exactly as it was consumed.
 * The payload is served here and nowhere else, and is never written to the logs.
 */
public record DeadLetterEventDetailResponse(DeadLetterEventResponse event, String payload) {
}
