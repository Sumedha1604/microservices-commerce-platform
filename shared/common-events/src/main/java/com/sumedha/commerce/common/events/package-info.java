/**
 * Framework-free Kafka event contracts shared between publishing and consuming services.
 *
 * <p>These types are a deliberately designed <em>published language</em> (v1 wire format),
 * not an export of any service's internal domain model. Rules for this package:
 *
 * <ul>
 *   <li>Immutable Java records, carrying data only - no business behaviour.</li>
 *   <li>No Spring, JPA, spring-kafka, or {@code common-core} dependency.</li>
 *   <li>The wire discriminator is the {@link com.sumedha.commerce.common.events.EventEnvelope#eventType()}
 *       string, never a Java class name - no polymorphic/default typing.</li>
 *   <li>Changes within v1 must be additive and backward compatible; a breaking change
 *       means a new {@code vN} topic and a bumped {@code schemaVersion}.</li>
 * </ul>
 */
package com.sumedha.commerce.common.events;
