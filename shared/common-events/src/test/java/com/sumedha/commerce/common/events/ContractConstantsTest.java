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
        assertEquals("order.compensation.v1", KafkaTopics.ORDER_COMPENSATION_V1);
        assertEquals("order.compensation.v1.DLT", KafkaTopics.ORDER_COMPENSATION_V1_DLT);
        assertTrue(KafkaTopics.ORDER_COMPENSATION_V1_DLT.startsWith(KafkaTopics.ORDER_COMPENSATION_V1));
    }

    @Test
    void eventTypeDiscriminatorsAreStable() {
        assertEquals("PaymentAuthorized", EventTypes.PAYMENT_AUTHORIZED);
        assertEquals("PaymentFailed", EventTypes.PAYMENT_FAILED);
        assertEquals("InventoryReleaseRequested", EventTypes.INVENTORY_RELEASE_REQUESTED);
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
