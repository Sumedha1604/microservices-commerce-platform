package com.sumedha.commerce.e2e;

import com.sumedha.commerce.e2e.config.ServiceUrls;

/**
 * Shared foundation for future E2E tests. It intentionally makes no network calls.
 */
public abstract class E2ETestBase {
    protected final ServiceUrls serviceUrls = ServiceUrls.load();
}
