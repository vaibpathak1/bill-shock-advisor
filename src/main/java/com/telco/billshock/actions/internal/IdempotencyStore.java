package com.telco.billshock.actions.internal;

import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.telco.billshock.actions.CommandResponse;
import com.telco.billshock.domain.AccountId;

/**
 * {@code idempotency_record} (actions.md §6.6). The first request with a key <b>claims</b> it
 * before doing anything else; a concurrent request with the same key blocks on the primary key
 * until the first transaction ends, then reads what it stored. {@code response_status = 0} marks
 * a claim whose request is still running.
 */
@Repository
class IdempotencyStore {

    static final int IN_PROGRESS = 0;

    private final JdbcClient jdbc;

    IdempotencyStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** What an earlier request with the same key left behind. */
    record Stored(String requestHash, int status, String body) {

        boolean inProgress() {
            return status == IN_PROGRESS;
        }

        CommandResponse response() {
            return new CommandResponse(status, body, status >= 400);
        }
    }

    /** Claims the key; empty = this request owns it now, else what the earlier request stored. */
    Optional<Stored> claim(AccountId account, String key, String requestHash) {
        int inserted = jdbc.sql("""
                INSERT INTO idempotency_record (account_id, idempotency_key, request_hash, response_status,
                                                response_body)
                VALUES (?, ?, ?, 0, '{}'::jsonb)
                ON CONFLICT (account_id, idempotency_key) DO NOTHING""")
            .params(account.value(), key, requestHash)
            .update();
        if (inserted == 1) {
            return Optional.empty();
        }
        return Optional.of(jdbc.sql("""
                SELECT request_hash, response_status, response_body::text FROM idempotency_record
                 WHERE account_id = ? AND idempotency_key = ?""")
            .params(account.value(), key)
            .query((rs, n) -> new Stored(rs.getString(1), rs.getInt(2), rs.getString(3)))
            .single());
    }

    /**
     * Stores the final response for replays and returns it as stored: {@code jsonb} normalises the
     * body, so the first response is the stored text too and every replay is identical to it.
     */
    CommandResponse complete(AccountId account, String key, CommandResponse response) {
        String stored = jdbc.sql("""
                UPDATE idempotency_record SET response_status = ?, response_body = ?::jsonb
                 WHERE account_id = ? AND idempotency_key = ?
                RETURNING response_body::text""")
            .params(response.status(), response.body(), account.value(), key)
            .query(String.class)
            .single();
        return new CommandResponse(response.status(), stored, response.problem());
    }
}
