package com.sumedha.commerce.order.messaging;

import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;
import org.springframework.kafka.support.KafkaHeaders;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Reads the {@code kafka_dlt-*} headers that {@code DeadLetterPublishingRecoverer} stamps on a
 * dead-lettered record.
 *
 * <p>Everything here is best-effort and null-tolerant: a record produced by hand, by an older
 * recoverer, or replayed by an operator may be missing any of them, and a missing header must
 * degrade the inspection record rather than fail ingestion.
 *
 * <p>{@code kafka_dlt-exception-fqcn} is read as <em>text</em> only. It is never resolved to a
 * Java type, so no Kafka-carried class name influences control flow - the same rule the payload
 * parser follows.
 */
final class DeadLetterHeaders {

    /** W3C trace context, stamped by KafkaTemplate observation on the original send. */
    static final String TRACEPARENT = "traceparent";

    private DeadLetterHeaders() {
        throw new UnsupportedOperationException("Utility class");
    }

    static String string(Headers headers, String name) {
        Header header = headers.lastHeader(name);
        if (header == null || header.value() == null) {
            return null;
        }
        return new String(header.value(), StandardCharsets.UTF_8);
    }

    /** DLT integer headers are written as raw 4-byte big-endian, not as text. */
    static Integer integer(Headers headers, String name) {
        Header header = headers.lastHeader(name);
        if (header == null || header.value() == null || header.value().length != Integer.BYTES) {
            return null;
        }
        return ByteBuffer.wrap(header.value()).getInt();
    }

    /** DLT long headers are written as raw 8-byte big-endian, not as text. */
    static Long longValue(Headers headers, String name) {
        Header header = headers.lastHeader(name);
        if (header == null || header.value() == null || header.value().length != Long.BYTES) {
            return null;
        }
        return ByteBuffer.wrap(header.value()).getLong();
    }

    static String originalTopic(Headers headers) {
        return string(headers, KafkaHeaders.DLT_ORIGINAL_TOPIC);
    }

    static Integer originalPartition(Headers headers) {
        return integer(headers, KafkaHeaders.DLT_ORIGINAL_PARTITION);
    }

    static Long originalOffset(Headers headers) {
        return longValue(headers, KafkaHeaders.DLT_ORIGINAL_OFFSET);
    }

    static String consumerGroup(Headers headers) {
        return string(headers, KafkaHeaders.DLT_ORIGINAL_CONSUMER_GROUP);
    }

    /**
     * The most diagnostically useful exception name available.
     *
     * <p>Spring wraps a listener failure in {@code ListenerExecutionFailedException}, so
     * {@code kafka_dlt-exception-fqcn} usually names the wrapper rather than the real reason.
     * The cause header is preferred when present and the wrapper is the fallback.
     */
    static String exceptionClass(Headers headers) {
        String cause = string(headers, KafkaHeaders.DLT_EXCEPTION_CAUSE_FQCN);
        return cause != null && !cause.isBlank() ? cause : string(headers, KafkaHeaders.DLT_EXCEPTION_FQCN);
    }

    static String exceptionMessage(Headers headers) {
        return string(headers, KafkaHeaders.DLT_EXCEPTION_MESSAGE);
    }

    static String traceparent(Headers headers) {
        return string(headers, TRACEPARENT);
    }
}
