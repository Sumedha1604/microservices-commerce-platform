package com.sumedha.commerce.order.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The dead-letter counters, including the exact metric names published to Prometheus.
 *
 * <p>Names are asserted against a real {@link PrometheusMeterRegistry} scrape rather than against
 * the Micrometer ids, because the scrape names are what {@code docs/events/dlt-operations.md}
 * documents and what a dashboard or alert would be written against. Renaming a counter silently
 * breaks those, so the mapping is pinned here.
 *
 * <p>Cardinality is asserted too: capture counters carry no tags, and replay carries exactly one
 * low-cardinality {@code result} tag. Nothing derived from an event id, order id, topic or
 * exception message may ever become a tag.
 */
class DeadLetterMetricsTest {

    // ---------- counting ----------

    @Test
    void eachCounterCountsOnlyItsOwnEvent() {
        MeterRegistry registry = new SimpleMeterRegistry();
        DeadLetterMetrics metrics = new DeadLetterMetrics(registry);

        metrics.captured();
        metrics.captured();
        metrics.captured();
        metrics.duplicateIgnored();
        metrics.duplicateIgnored();
        metrics.replaySucceeded();
        metrics.replayFailed();
        metrics.replayFailed();
        metrics.replayFailed();
        metrics.replayFailed();

        assertEquals(3.0, counter(registry, "order.dlt.captured").count());
        assertEquals(2.0, counter(registry, "order.dlt.duplicate.ignored").count());
        assertEquals(1.0, replayCounter(registry, "success").count());
        assertEquals(4.0, replayCounter(registry, "failure").count());
    }

    @Test
    void allCountersAreRegisteredEagerlySoTheyReadZeroBeforeAnythingHappens() {
        MeterRegistry registry = new SimpleMeterRegistry();
        new DeadLetterMetrics(registry);

        assertEquals(0.0, counter(registry, "order.dlt.captured").count(),
                "a counter that only appears after the first failure is useless for alerting");
        assertEquals(0.0, counter(registry, "order.dlt.duplicate.ignored").count());
        assertEquals(0.0, replayCounter(registry, "success").count());
        assertEquals(0.0, replayCounter(registry, "failure").count());
    }

    @Test
    void theSuccessAndFailureSeriesAreIndependent() {
        MeterRegistry registry = new SimpleMeterRegistry();
        DeadLetterMetrics metrics = new DeadLetterMetrics(registry);

        metrics.replayFailed();

        assertEquals(0.0, replayCounter(registry, "success").count(),
                "a failed replay must never register as a success");
        assertEquals(1.0, replayCounter(registry, "failure").count());
    }

    // ---------- the documented Prometheus names ----------

    @Test
    void publishesExactlyTheMetricNamesTheOperatorGuideDocuments() {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        DeadLetterMetrics metrics = new DeadLetterMetrics(registry);

        metrics.captured();
        metrics.duplicateIgnored();
        metrics.replaySucceeded();
        metrics.replayFailed();

        String scrape = registry.scrape();

        assertTrue(scrape.contains("order_dlt_captured_total"),
                () -> "order_dlt_captured_total missing from scrape:\n" + scrape);
        assertTrue(scrape.contains("order_dlt_duplicate_ignored_total"),
                () -> "order_dlt_duplicate_ignored_total missing from scrape:\n" + scrape);
        assertTrue(scrape.contains("order_dlt_replay_total{result=\"success\"}"),
                () -> "order_dlt_replay_total{result=\"success\"} missing from scrape:\n" + scrape);
        assertTrue(scrape.contains("order_dlt_replay_total{result=\"failure\"}"),
                () -> "order_dlt_replay_total{result=\"failure\"} missing from scrape:\n" + scrape);
    }

    // ---------- cardinality is fixed by construction ----------

    @Test
    void registersThreeMetricNamesAndNothingElse() {
        MeterRegistry registry = new SimpleMeterRegistry();
        new DeadLetterMetrics(registry);

        Set<String> names = registry.getMeters().stream()
                .map(meter -> meter.getId().getName())
                .collect(Collectors.toSet());

        assertEquals(Set.of("order.dlt.captured", "order.dlt.duplicate.ignored", "order.dlt.replay"), names);
        assertEquals(4, registry.getMeters().size(), "three names, four series: replay is split by result");
    }

    @Test
    void theCaptureCountersCarryNoTagsAndReplayCarriesOnlyResult() {
        MeterRegistry registry = new SimpleMeterRegistry();
        new DeadLetterMetrics(registry);

        assertEquals(List.of(), counter(registry, "order.dlt.captured").getId().getTags());
        assertEquals(List.of(), counter(registry, "order.dlt.duplicate.ignored").getId().getTags());

        for (Meter meter : registry.getMeters()) {
            if (!meter.getId().getName().equals("order.dlt.replay")) {
                continue;
            }
            List<Tag> tags = meter.getId().getTags();
            assertEquals(1, tags.size(), "replay must carry exactly one tag");
            assertEquals("result", tags.getFirst().getKey());
            assertTrue(Set.of("success", "failure").contains(tags.getFirst().getValue()),
                    "the result tag is a closed two-value set, never anything unbounded");
        }
    }

    @Test
    void everyCounterCarriesADescription() {
        MeterRegistry registry = new SimpleMeterRegistry();
        new DeadLetterMetrics(registry);

        for (Meter meter : registry.getMeters()) {
            String description = meter.getId().getDescription();
            assertNotNull(description, () -> meter.getId().getName() + " has no description");
            assertTrue(!description.isBlank());
        }
    }

    // ---------- helpers ----------

    private static Counter counter(MeterRegistry registry, String name) {
        Counter counter = registry.find(name).counter();
        assertNotNull(counter, () -> "no counter registered under " + name);
        return counter;
    }

    private static Counter replayCounter(MeterRegistry registry, String result) {
        Counter counter = registry.find("order.dlt.replay").tag("result", result).counter();
        assertNotNull(counter, () -> "no order.dlt.replay counter tagged result=" + result);
        return counter;
    }
}
