package com.sumedha.commerce.order.enums;

/**
 * Operational state of one captured dead-letter record.
 *
 * <p>Deliberately three flat values, not a workflow: a record is captured ({@code NEW}), has been
 * republished to its original topic and acknowledged ({@code REPLAYED}), or a replay attempt
 * failed at the broker ({@code REPLAY_FAILED}). {@code REPLAY_FAILED} is recoverable - the stored
 * payload is untouched and the record can be replayed again.
 */
public enum DeadLetterStatus {
    NEW,
    REPLAYED,
    REPLAY_FAILED
}
