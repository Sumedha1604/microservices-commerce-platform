package com.sumedha.commerce.order.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The replay send timeout, and the invariant that it must be a positive duration.
 *
 * <p>This matters because the timeout is what stops a replay request blocking forever on an
 * unreachable broker. A zero or negative value would make every replay fail instantly while
 * still incrementing {@code replay_count}, so it is rejected at startup rather than discovered
 * in production.
 */
class DeadLetterReplayPropertiesTest {

    @Test
    void acceptsAPositiveTimeout() {
        assertEquals(Duration.ofSeconds(10),
                new DeadLetterReplayProperties(Duration.ofSeconds(10)).sendTimeout());
    }

    @Test
    void rejectsAMissingTimeout() {
        IllegalArgumentException rejected =
                assertThrows(IllegalArgumentException.class, () -> new DeadLetterReplayProperties(null));

        assertTrue(rejected.getMessage().contains("order.dlt.replay.send-timeout"),
                "the message must name the property an operator has to fix");
    }

    @Test
    void rejectsAZeroOrNegativeTimeout() {
        assertThrows(IllegalArgumentException.class,
                () -> new DeadLetterReplayProperties(Duration.ZERO));
        assertThrows(IllegalArgumentException.class,
                () -> new DeadLetterReplayProperties(Duration.ofSeconds(-1)));
        assertThrows(IllegalArgumentException.class,
                () -> new DeadLetterReplayProperties(Duration.ofMillis(-1)));
    }

    /** Pins the configuration prefix that {@code application.yml} and the ops guide both use. */
    @Test
    void bindsFromTheOrderDltReplayPrefix() {
        DeadLetterReplayProperties bound = new Binder(new MapConfigurationPropertySource(
                Map.of("order.dlt.replay.send-timeout", "10s")))
                .bind("order.dlt.replay", DeadLetterReplayProperties.class)
                .get();

        assertEquals(Duration.ofSeconds(10), bound.sendTimeout());
    }

    @Test
    void aMisconfiguredTimeoutFailsBindingRatherThanBeingSilentlyAccepted() {
        Binder binder = new Binder(new MapConfigurationPropertySource(
                Map.of("order.dlt.replay.send-timeout", "0s")));

        assertThrows(RuntimeException.class,
                () -> binder.bind("order.dlt.replay", DeadLetterReplayProperties.class));
    }
}
