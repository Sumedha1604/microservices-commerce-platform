package com.sumedha.commerce.order.messaging;

import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.KafkaHeaders;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Decoding of the {@code kafka_dlt-*} headers.
 *
 * <p>Two things are being pinned down here. First, the integer and long headers are written by
 * {@code DeadLetterPublishingRecoverer} as raw big-endian bytes, not as text - reading them as
 * strings would yield nonsense, so the wire encoding is asserted explicitly. Second, every
 * accessor must degrade to {@code null} rather than throw: these headers can be missing or
 * malformed on a hand-produced or replayed record, and a missing header must cost us one column,
 * never the whole inspection row.
 */
class DeadLetterHeadersTest {

    private static Headers headers() {
        return new RecordHeaders();
    }

    private static Headers with(String name, byte[] value) {
        Headers headers = headers();
        headers.add(new RecordHeader(name, value));
        return headers;
    }

    private static byte[] utf8(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] intBytes(int value) {
        return ByteBuffer.allocate(Integer.BYTES).putInt(value).array();
    }

    private static byte[] longBytes(long value) {
        return ByteBuffer.allocate(Long.BYTES).putLong(value).array();
    }

    // ---------- the recoverer's own encoding ----------

    @Test
    void decodesTheOriginalTopicPartitionAndOffsetAsTheRecovererWroteThem() {
        Headers headers = headers();
        headers.add(new RecordHeader(KafkaHeaders.DLT_ORIGINAL_TOPIC, utf8("payment.events.v1")));
        headers.add(new RecordHeader(KafkaHeaders.DLT_ORIGINAL_PARTITION, intBytes(2)));
        headers.add(new RecordHeader(KafkaHeaders.DLT_ORIGINAL_OFFSET, longBytes(4_294_967_296L)));
        headers.add(new RecordHeader(KafkaHeaders.DLT_ORIGINAL_CONSUMER_GROUP, utf8("order-service")));

        assertEquals("payment.events.v1", DeadLetterHeaders.originalTopic(headers));
        assertEquals(2, DeadLetterHeaders.originalPartition(headers));
        assertEquals(4_294_967_296L, DeadLetterHeaders.originalOffset(headers),
                "an offset beyond int range must survive the round trip");
        assertEquals("order-service", DeadLetterHeaders.consumerGroup(headers));
    }

    @Test
    void decodesZeroAndNegativeNumericHeaders() {
        assertEquals(0, DeadLetterHeaders.integer(with("n", intBytes(0)), "n"));
        assertEquals(-1, DeadLetterHeaders.integer(with("n", intBytes(-1)), "n"));
        assertEquals(0L, DeadLetterHeaders.longValue(with("n", longBytes(0L)), "n"));
        assertEquals(Long.MAX_VALUE, DeadLetterHeaders.longValue(with("n", longBytes(Long.MAX_VALUE)), "n"));
    }

    @Test
    void readsTheTraceparentForCorrelationWithTheOriginalSend() {
        String traceparent = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

        assertEquals(traceparent,
                DeadLetterHeaders.traceparent(with(DeadLetterHeaders.TRACEPARENT, utf8(traceparent))));
    }

    // ---------- the cause is preferred over Spring's wrapper ----------

    /**
     * Spring wraps a listener failure in {@code ListenerExecutionFailedException}, so the plain
     * fqcn header usually names the wrapper. The cause is what actually tells an operator why the
     * record failed, so it wins whenever it is present.
     */
    @Test
    void prefersTheCauseOverTheWrapperWhenBothArePresent() {
        Headers headers = headers();
        headers.add(new RecordHeader(KafkaHeaders.DLT_EXCEPTION_FQCN,
                utf8("org.springframework.kafka.listener.ListenerExecutionFailedException")));
        headers.add(new RecordHeader(KafkaHeaders.DLT_EXCEPTION_CAUSE_FQCN,
                utf8("com.sumedha.commerce.order.messaging.NonRetryableEventException")));

        assertEquals("com.sumedha.commerce.order.messaging.NonRetryableEventException",
                DeadLetterHeaders.exceptionClass(headers));
    }

    @Test
    void fallsBackToTheWrapperWhenNoCauseWasRecorded() {
        Headers headers = with(KafkaHeaders.DLT_EXCEPTION_FQCN,
                utf8("org.springframework.kafka.listener.ListenerExecutionFailedException"));

        assertEquals("org.springframework.kafka.listener.ListenerExecutionFailedException",
                DeadLetterHeaders.exceptionClass(headers));
    }

    @Test
    void fallsBackToTheWrapperWhenTheCauseHeaderIsBlank() {
        Headers headers = headers();
        headers.add(new RecordHeader(KafkaHeaders.DLT_EXCEPTION_FQCN, utf8("com.example.Wrapper")));
        headers.add(new RecordHeader(KafkaHeaders.DLT_EXCEPTION_CAUSE_FQCN, utf8("   ")));

        assertEquals("com.example.Wrapper", DeadLetterHeaders.exceptionClass(headers),
                "a blank cause is no cause at all");
    }

    @Test
    void reportsNoExceptionClassWhenNeitherHeaderIsPresent() {
        assertNull(DeadLetterHeaders.exceptionClass(headers()));
        assertNull(DeadLetterHeaders.exceptionMessage(headers()));
    }

    /**
     * The exception name is carried as diagnostic text and is never resolved to a Java type, so an
     * arbitrary or hostile class name arriving over Kafka is returned verbatim and influences
     * nothing.
     */
    @Test
    void anArbitraryExceptionClassNameIsReturnedAsPlainTextAndNeverResolved() {
        Headers headers = with(KafkaHeaders.DLT_EXCEPTION_CAUSE_FQCN, utf8("java.lang.Runtime; rm -rf /"));

        assertEquals("java.lang.Runtime; rm -rf /", DeadLetterHeaders.exceptionClass(headers));
    }

    // ---------- missing and malformed headers degrade to null ----------

    @Test
    void everyAccessorReturnsNullOnHeadersThatCarryNothing() {
        Headers empty = headers();

        assertNull(DeadLetterHeaders.originalTopic(empty));
        assertNull(DeadLetterHeaders.originalPartition(empty));
        assertNull(DeadLetterHeaders.originalOffset(empty));
        assertNull(DeadLetterHeaders.consumerGroup(empty));
        assertNull(DeadLetterHeaders.exceptionClass(empty));
        assertNull(DeadLetterHeaders.exceptionMessage(empty));
        assertNull(DeadLetterHeaders.traceparent(empty));
    }

    @Test
    void aNullHeaderValueIsTreatedAsAbsent() {
        assertNull(DeadLetterHeaders.string(with("s", null), "s"));
        assertNull(DeadLetterHeaders.integer(with("n", null), "n"));
        assertNull(DeadLetterHeaders.longValue(with("n", null), "n"));
    }

    /**
     * A wrong-width numeric header is refused rather than partially decoded - a 2-byte value read
     * as an int would silently produce a fabricated partition number.
     */
    @Test
    void aNumericHeaderOfTheWrongWidthIsRefusedRatherThanMisdecoded() {
        assertNull(DeadLetterHeaders.integer(with("n", new byte[]{1, 2}), "n"), "too short for an int");
        assertNull(DeadLetterHeaders.integer(with("n", longBytes(1L)), "n"), "too long for an int");
        assertNull(DeadLetterHeaders.integer(with("n", new byte[0]), "n"));
        assertNull(DeadLetterHeaders.longValue(with("n", intBytes(1)), "n"), "too short for a long");
        assertNull(DeadLetterHeaders.longValue(with("n", new byte[9]), "n"), "too long for a long");
    }

    /** Numeric headers written as text - a hand-produced record - must not decode as numbers. */
    @Test
    void aTextEncodedPartitionIsNotMistakenForABinaryOne() {
        assertNull(DeadLetterHeaders.integer(with(KafkaHeaders.DLT_ORIGINAL_PARTITION, utf8("2")),
                KafkaHeaders.DLT_ORIGINAL_PARTITION), "\"2\" is one byte, not four");
        assertNull(DeadLetterHeaders.originalOffset(
                with(KafkaHeaders.DLT_ORIGINAL_OFFSET, utf8("41"))));
    }

    @Test
    void anEmptyStringHeaderIsReadAsAnEmptyStringNotNull() {
        assertEquals("", DeadLetterHeaders.string(with("s", new byte[0]), "s"));
    }

    @Test
    void theLastHeaderWinsWhenAValueWasStampedMoreThanOnce() {
        Headers headers = headers();
        headers.add(new RecordHeader(KafkaHeaders.DLT_ORIGINAL_TOPIC, utf8("first.topic")));
        headers.add(new RecordHeader(KafkaHeaders.DLT_ORIGINAL_TOPIC, utf8("second.topic")));

        assertEquals("second.topic", DeadLetterHeaders.originalTopic(headers));
    }
}
