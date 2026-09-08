package com.sumedha.commerce.payment.messaging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sumedha.commerce.payment.dto.request.AuthorizePaymentRequest;
import com.sumedha.commerce.payment.dto.request.CreatePaymentRequest;
import com.sumedha.commerce.payment.entity.Payment;
import com.sumedha.commerce.payment.enums.PaymentStatus;
import com.sumedha.commerce.payment.repository.PaymentRepository;
import com.sumedha.commerce.payment.service.PaymentService;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F: when Kafka is unreachable, the after-commit publication fails but the payment
 * transition stays committed - no rollback illusion, no exception to the caller.
 *
 * <p>No broker is started; {@code spring.kafka.bootstrap-servers} points at a dead port and
 * the producer is told to give up quickly.
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
        "spring.kafka.bootstrap-servers=localhost:59998",
        "payment.outbox.enabled=false",
        "spring.kafka.producer.retries=0",
        "spring.kafka.producer.properties.enable.idempotence=false",
        "spring.kafka.producer.properties.max.block.ms=1000",
        "spring.kafka.producer.properties.delivery.timeout.ms=1500",
        "spring.kafka.producer.properties.request.timeout.ms=1000",
        // no broker to talk to: never block startup or shutdown on admin calls
        "spring.kafka.admin.auto-create=false",
        "spring.kafka.admin.fail-fast=false",
        "spring.kafka.admin.close-timeout=1s",
        "spring.kafka.admin.operation-timeout=1s",
        "spring.kafka.admin.properties.default.api.timeout.ms=1000",
        "spring.kafka.admin.properties.request.timeout.ms=1000",
        "management.opentelemetry.tracing.export.otlp.endpoint=http://localhost:59999/v1/traces"
})
class PaymentKafkaPublishFailureIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("payment_test")
            .withUsername("payment_user")
            .withPassword("payment_user");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired private PaymentService paymentService;
    @Autowired private PaymentRepository payments;
    @Autowired private PaymentOutboxBatchProcessor outboxProcessor;
    @Autowired private com.sumedha.commerce.payment.repository.PaymentOutboxEventRepository outboxEvents;

    @Test
    void kafkaOutageAfterCommitLeavesThePaymentAuthorizedAndDoesNotThrow() throws Exception {
        Logger publisherLogger = (Logger) LoggerFactory.getLogger(PaymentOutboxBatchProcessor.class);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        publisherLogger.addAppender(logs);
        try {
            UUID orderId = UUID.randomUUID();
            UUID paymentId = paymentService.create(
                    new CreatePaymentRequest(orderId, UUID.randomUUID(), new BigDecimal("10.00"), "USD")).id();

            assertDoesNotThrow(() ->
                    paymentService.authorize(paymentId, new AuthorizePaymentRequest("stripe", "ref-1")));

            Payment persisted = payments.findById(paymentId).orElseThrow();
            assertEquals(PaymentStatus.AUTHORIZED, persisted.getStatus(),
                    "the committed payment transition must survive a Kafka publication failure");

            assertDoesNotThrow(outboxProcessor::publishNextBatch);
            var outbox = outboxEvents.findAll().getFirst();
            assertEquals(com.sumedha.commerce.payment.enums.OutboxEventStatus.PENDING, outbox.getStatus());
            assertEquals(1, outbox.getAttemptCount());
            assertTrue(outbox.getLastError() != null && !outbox.getLastError().isBlank());
            assertTrue(awaitPublishFailureLogged(logs),
                    "the retryable publication failure must be surfaced as a WARN");
        } finally {
            publisherLogger.detachAppender(logs);
        }
    }

    private static boolean awaitPublishFailureLogged(ListAppender<ILoggingEvent> logs) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline) {
            boolean logged = logs.list.stream().anyMatch(event -> event.getLevel() == Level.WARN
                    && event.getFormattedMessage().contains("retained for retry"));
            if (logged) {
                return true;
            }
            Thread.sleep(200);
        }
        return false;
    }
}
