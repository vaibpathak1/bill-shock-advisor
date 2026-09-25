-- ProposedAction workflow, idempotency, runtime flags and the immutable audit log.
-- Design: ADR-004 (state machine), data-architecture.md §4.4, §4.5, §7.1 (X2–X4).
-- Owner modules: actions, audit. audit_events partitions are created in V6.

CREATE TABLE proposed_action (
    action_id           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    account_id          bigint        NOT NULL REFERENCES account (account_id),
    conversation_id     uuid          REFERENCES conversation (conversation_id),
    action_type         text          NOT NULL CHECK (action_type IN ('GOODWILL_CREDIT', 'VAS_UNSUBSCRIBE', 'THIRD_PARTY_BARRING',
                                                                      'PLAN_CHANGE', 'ADD_ON',
                                                                      'DISPUTE', 'ESCALATION')),
    status              text          NOT NULL CHECK (status IN ('PENDING_CONFIRMATION', 'ESCALATED', 'AWAITING_SUPERVISOR',
                                                                 'APPROVED', 'AUTO_APPROVED', 'REJECTED', 'EXPIRED',
                                                                 'EXECUTING', 'EXECUTED', 'FAILED')),
    amount              numeric(14,2) CHECK (amount >= 0),          -- before GST (Q-20)
    gst_amount          numeric(14,2) CHECK (gst_amount >= 0),      -- credits/refunds include GST (Q-20)
    currency            char(3)       NOT NULL DEFAULT 'INR',
    params              jsonb         NOT NULL DEFAULT '{}'::jsonb,
    guardrail_result    jsonb,
    requires_supervisor boolean       NOT NULL DEFAULT false,
    idempotency_key     text          UNIQUE,
    expires_at          timestamptz,
    decided_by          text,
    decided_at          timestamptz,
    executed_at         timestamptz,
    external_ref        text,
    failure_reason      text,
    version             integer       NOT NULL DEFAULT 0,           -- optimistic locking
    created_at          timestamptz   NOT NULL DEFAULT now(),
    CHECK ((amount IS NULL) = (gst_amount IS NULL))
);

-- X2: guardrail "no goodwill credit in the last 6 months"
CREATE INDEX proposed_action_credit_history_idx ON proposed_action (account_id, executed_at)
    WHERE action_type = 'GOODWILL_CREDIT' AND status = 'EXECUTED';
-- X3: GET /api/v1/actions?status=
CREATE INDEX proposed_action_account_status_idx ON proposed_action (account_id, status, created_at DESC);
-- Expiry sweep
CREATE INDEX proposed_action_expiry_idx ON proposed_action (expires_at)
    WHERE status = 'PENDING_CONFIRMATION';

CREATE TABLE idempotency_record (
    account_id      bigint      NOT NULL REFERENCES account (account_id),
    idempotency_key text        NOT NULL,
    request_hash    text        NOT NULL,
    response_status smallint    NOT NULL,
    response_body   jsonb       NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (account_id, idempotency_key)
);

CREATE TABLE feature_flag (
    flag_key   text        PRIMARY KEY,
    flag_value text        NOT NULL,
    updated_by text        NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE SEQUENCE audit_events_audit_id_seq AS bigint;

CREATE TABLE audit_events (
    audit_id        bigint      NOT NULL DEFAULT nextval('audit_events_audit_id_seq'),
    occurred_at     timestamptz NOT NULL,
    account_id      bigint,
    conversation_id uuid,
    correlation_id  text,
    actor_type      text        NOT NULL CHECK (actor_type IN ('CUSTOMER', 'CARE_AGENT', 'SUPERVISOR', 'ADMIN', 'AGENT', 'SYSTEM')),
    actor_ref       text,                                       -- masked
    event_type      text        NOT NULL,
    tool_name       text,
    action_id       bigint,
    prompt_version  text,
    payload         jsonb       NOT NULL DEFAULT '{}'::jsonb,   -- masked
    result_hash     text,
    PRIMARY KEY (occurred_at, audit_id)
) PARTITION BY RANGE (occurred_at);

ALTER SEQUENCE audit_events_audit_id_seq OWNED BY audit_events.audit_id;

-- X4: GET /api/v1/audit?conversationId=
CREATE INDEX audit_events_conversation_idx ON audit_events (conversation_id, occurred_at)
    WHERE conversation_id IS NOT NULL;

-- Immutable audit (SPEC §4.3 rule 5): rows can be inserted, never changed or deleted.
-- Retention drops whole partitions after archiving (data-architecture.md §5), which this
-- row-level trigger does not block.
CREATE FUNCTION audit_events_immutable() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION 'audit_events is append-only (% not allowed)', TG_OP;
END;
$$;

CREATE TRIGGER audit_events_no_update_delete
    BEFORE UPDATE OR DELETE ON audit_events
    FOR EACH ROW EXECUTE FUNCTION audit_events_immutable();
