package com.sumedha.commerce.e2e.config;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Resolves E2E service URLs from JVM properties, environment variables, then defaults.
 */
public record ServiceUrls(
        String product,
        String inventory,
        String cart,
        String order,
        String payment,
        String checkout
) {
    private static final String CONFIG_RESOURCE = "e2e.properties";

    public static ServiceUrls load() {
        Properties defaults = new Properties();
        try (InputStream input = ServiceUrls.class.getClassLoader().getResourceAsStream(CONFIG_RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException("Missing E2E configuration resource: " + CONFIG_RESOURCE);
            }
            defaults.load(input);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to load E2E configuration", exception);
        }

        return new ServiceUrls(
                resolve("e2e.product.base-url", "E2E_PRODUCT_BASE_URL", defaults),
                resolve("e2e.inventory.base-url", "E2E_INVENTORY_BASE_URL", defaults),
                resolve("e2e.cart.base-url", "E2E_CART_BASE_URL", defaults),
                resolve("e2e.order.base-url", "E2E_ORDER_BASE_URL", defaults),
                resolve("e2e.payment.base-url", "E2E_PAYMENT_BASE_URL", defaults),
                resolve("e2e.checkout.base-url", "E2E_CHECKOUT_BASE_URL", defaults)
        );
    }

    private static String resolve(String property, String environmentVariable, Properties defaults) {
        return firstNonBlank(System.getProperty(property), System.getenv(environmentVariable), defaults.getProperty(property));
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        throw new IllegalStateException("No E2E service URL was configured");
    }
}
