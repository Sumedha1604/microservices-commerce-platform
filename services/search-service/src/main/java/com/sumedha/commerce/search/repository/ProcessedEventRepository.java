package com.sumedha.commerce.search.repository;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Types;
import java.util.UUID;

@Repository
public class ProcessedEventRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public ProcessedEventRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Claims {@code eventId} for the current transaction, or reports that it is already taken.
     *
     * <p>A guarded insert rather than "exists, then insert": when two transactions claim the same id
     * concurrently, PostgreSQL makes the second wait for the first; it then inserts nothing and gets
     * {@code 0} if the first committed, or claims the id and gets {@code 1} if the first rolled back.
     *
     * @return {@code 1} if this transaction claimed the event, {@code 0} if it was already claimed
     */
    public int claim(UUID eventId, String eventType, UUID productId) {
        return jdbc.update("insert into processed_event (event_id, event_type, product_id, processed_at) "
                        + "values (:eventId, :eventType, :productId, now()) on conflict (event_id) do nothing",
                new MapSqlParameterSource()
                        .addValue("eventId", eventId, Types.OTHER)
                        .addValue("eventType", eventType)
                        .addValue("productId", productId, Types.OTHER));
    }
}
