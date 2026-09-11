package com.sumedha.commerce.common.events;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Locks the centralised topic names, event-type discriminators, and schema version. */
class ContractConstantsTest {

    @Test
    void topicNamesAreStableAndVersioned() {
        assertEquals("payment.events.v1", KafkaTopics.PAYMENT_EVENTS_V1);
        assertEquals("payment.events.v1.DLT", KafkaTopics.PAYMENT_EVENTS_V1_DLT);
        assertTrue(KafkaTopics.PAYMENT_EVENTS_V1_DLT.startsWith(KafkaTopics.PAYMENT_EVENTS_V1));
        assertEquals("payment.events.v1.notification.DLT", KafkaTopics.PAYMENT_EVENTS_V1_NOTIFICATION_DLT);
        assertTrue(KafkaTopics.PAYMENT_EVENTS_V1_NOTIFICATION_DLT.startsWith(KafkaTopics.PAYMENT_EVENTS_V1));
        assertTrue(!KafkaTopics.PAYMENT_EVENTS_V1_NOTIFICATION_DLT.equals(KafkaTopics.PAYMENT_EVENTS_V1_DLT),
                "each consumer of payment.events.v1 owns a distinct dead-letter topic");
        assertEquals("order.compensation.v1", KafkaTopics.ORDER_COMPENSATION_V1);
        assertEquals("order.compensation.v1.DLT", KafkaTopics.ORDER_COMPENSATION_V1_DLT);
        assertTrue(KafkaTopics.ORDER_COMPENSATION_V1_DLT.startsWith(KafkaTopics.ORDER_COMPENSATION_V1));
        assertEquals("product.events.v1", KafkaTopics.PRODUCT_EVENTS_V1);
        assertEquals("product.events.v1.search.DLT", KafkaTopics.PRODUCT_EVENTS_V1_SEARCH_DLT);
        assertTrue(KafkaTopics.PRODUCT_EVENTS_V1_SEARCH_DLT.startsWith(KafkaTopics.PRODUCT_EVENTS_V1));
    }

    @Test
    void eventTypeDiscriminatorsAreStable() {
        assertEquals("PaymentAuthorized", EventTypes.PAYMENT_AUTHORIZED);
        assertEquals("PaymentFailed", EventTypes.PAYMENT_FAILED);
        assertEquals("InventoryReleaseRequested", EventTypes.INVENTORY_RELEASE_REQUESTED);
        assertEquals("ProductUpserted", EventTypes.PRODUCT_UPSERTED);
        assertEquals("ProductDeleted", EventTypes.PRODUCT_DELETED);
    }

    @Test
    void schemaVersionIsOne() {
        assertEquals(1, EventEnvelope.SCHEMA_VERSION_V1);
    }

    @Test
    void constantsHoldersAreNonInstantiableUtilityClasses() throws NoSuchMethodException {
        assertUtilityClass(EventTypes.class);
        assertUtilityClass(KafkaTopics.class);
    }

    private static void assertUtilityClass(Class<?> type) throws NoSuchMethodException {
        assertTrue(Modifier.isFinal(type.getModifiers()), type + " must be final");
        Constructor<?> constructor = type.getDeclaredConstructor();
        constructor.setAccessible(true);

        InvocationTargetException thrown =
                assertThrows(InvocationTargetException.class, constructor::newInstance);
        assertInstanceOf(UnsupportedOperationException.class, thrown.getCause());
    }
}
