-- Conversations, chat memory and LLM call metering.
-- Design: data-architecture.md §4.6, §5, §7.1 (Q5); llm-architecture.md §8, §12. Owner module: agent.
-- Partitions are created in V6. PostgreSQL 16 does not support identity columns on
-- partitioned tables, so llm_call_log uses a plain sequence.

CREATE TABLE conversation (
    conversation_id uuid          PRIMARY KEY,                      -- UUIDv7: carries the partition month
    account_id      bigint        NOT NULL REFERENCES account (account_id),
    channel         text          NOT NULL CHECK (channel IN ('WEB', 'APP', 'CARE_AGENT')),
    started_at      timestamptz   NOT NULL,
    locale          text          NOT NULL DEFAULT 'en-IN',
    prompt_version  text          NOT NULL,
    model           text          NOT NULL,
    autonomy_level  smallint      NOT NULL CHECK (autonomy_level BETWEEN 0 AND 2),
    llm_cost_usd    numeric(14,6) NOT NULL DEFAULT 0 CHECK (llm_cost_usd >= 0),   -- Q-17 metering
    status          text          NOT NULL CHECK (status IN ('OPEN', 'CLOSED', 'ESCALATED'))
);

CREATE INDEX conversation_account_idx ON conversation (account_id, started_at DESC);

CREATE TABLE chat_messages (
    conversation_month date        NOT NULL CHECK (extract(day FROM conversation_month) = 1),
    conversation_id    uuid        NOT NULL REFERENCES conversation (conversation_id),
    seq                integer     NOT NULL CHECK (seq >= 0),
    account_id         bigint      NOT NULL,
    role               text        NOT NULL CHECK (role IN ('USER', 'ASSISTANT', 'TOOL', 'SYSTEM_CONTEXT')),
    content            text        NOT NULL,                        -- scrubbed (security.md §6)
    tool_name          text,
    token_count        integer     CHECK (token_count >= 0),
    client_message_id  uuid,                                        -- Q-16 (5b)
    created_at         timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (conversation_id, conversation_month, seq),         -- Q5: memory window
    UNIQUE (conversation_id, conversation_month, client_message_id)
) PARTITION BY RANGE (conversation_month);

CREATE SEQUENCE llm_call_log_call_id_seq AS bigint;

CREATE TABLE llm_call_log (
    call_id             bigint        NOT NULL DEFAULT nextval('llm_call_log_call_id_seq'),
    called_month        date          NOT NULL CHECK (extract(day FROM called_month) = 1),
    called_at           timestamptz   NOT NULL,
    conversation_id     uuid,                                       -- NULL for proactive calls
    model               text          NOT NULL,
    prompt_version      text          NOT NULL,
    input_tokens        integer       NOT NULL CHECK (input_tokens >= 0),
    cache_write_tokens  integer       NOT NULL DEFAULT 0 CHECK (cache_write_tokens >= 0),
    cache_read_tokens   integer       NOT NULL DEFAULT 0 CHECK (cache_read_tokens >= 0),
    output_tokens       integer       NOT NULL CHECK (output_tokens >= 0),
    cost_usd            numeric(14,6) NOT NULL CHECK (cost_usd >= 0),
    latency_ms          integer       NOT NULL CHECK (latency_ms >= 0),
    provider_request_id text,
    outcome             text          NOT NULL CHECK (outcome IN ('OK', 'TIMEOUT', 'RATE_LIMITED', 'ERROR', 'CIRCUIT_OPEN')),
    PRIMARY KEY (called_month, call_id)
) PARTITION BY RANGE (called_month);

ALTER SEQUENCE llm_call_log_call_id_seq OWNED BY llm_call_log.call_id;
