package com.telco.billshock.audit;

/**
 * Writes {@link AuditEvent}s to {@code audit_events}. A call inside a transaction joins it, so
 * an action's state change and its audit row commit or roll back together (ADR-004, NFR-18).
 * A failed write throws: auditing fails closed.
 */
public interface AuditLog {

    void record(AuditEvent event);
}
