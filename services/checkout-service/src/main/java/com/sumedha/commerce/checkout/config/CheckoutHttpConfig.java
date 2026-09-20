package com.sumedha.commerce.checkout.config;

import io.micrometer.observation.ObservationRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedRetryMetrics;
import io.github.resilience4j.retry.RetryRegistry;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;

@Configuration
@EnableConfigurationProperties(CheckoutHttpProperties.class)
public class CheckoutHttpConfig {

    @Bean
    RestClient.Builder checkoutRestClientBuilder(
            CheckoutHttpProperties properties,
            ObservationRegistry observationRegistry) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());
        return RestClient.builder()
                .requestFactory(requestFactory)
                .observationRegistry(observationRegistry);
    }

    @Bean
    InitializingBean checkoutRetryMetrics(RetryRegistry retryRegistry, MeterRegistry meterRegistry) {
        return () -> TaggedRetryMetrics.ofRetryRegistry(retryRegistry).bindTo(meterRegistry);
    }
}
