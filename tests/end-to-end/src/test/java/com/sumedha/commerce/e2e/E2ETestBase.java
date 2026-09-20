package com.sumedha.commerce.e2e;

import com.sumedha.commerce.e2e.config.ServiceUrls;

/** Shared configuration foundation for end-to-end tests. */
public abstract class E2ETestBase {
    protected final ServiceUrls serviceUrls = ServiceUrls.load();
}
