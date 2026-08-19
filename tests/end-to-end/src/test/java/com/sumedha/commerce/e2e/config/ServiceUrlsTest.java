package com.sumedha.commerce.e2e.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ServiceUrlsTest {

    @Test
    void loadsDefaultLocalServiceUrls() {
        ServiceUrls urls = ServiceUrls.load();

        assertEquals("http://localhost:8083", urls.product());
        assertEquals("http://localhost:8084", urls.inventory());
        assertEquals("http://localhost:8085", urls.cart());
        assertEquals("http://localhost:8086", urls.order());
        assertEquals("http://localhost:8087", urls.payment());
        assertEquals("http://localhost:8088", urls.checkout());
    }
}
