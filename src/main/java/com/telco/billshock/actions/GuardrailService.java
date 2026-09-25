package com.telco.billshock.actions;

import com.telco.billshock.actions.guardrail.GuardrailChain;
import com.telco.billshock.actions.guardrail.GuardrailContextFactory;
import com.telco.billshock.domain.AccountId;
import org.springframework.stereotype.Service;

/**
 * The single entry point to the guardrails (ADR-004; deterministic-core.md §4). The caller
 * passes the account from the SecurityContext and the tool's request; every fact the rules
 * need is looked up server-side, so a request cannot supply or override one (§4.1).
 */
@Service
public class GuardrailService {

    private final GuardrailContextFactory contexts;
    private final GuardrailChain chain;

    public GuardrailService(GuardrailContextFactory contexts, GuardrailChain chain) {
        this.contexts = contexts;
        this.chain = chain;
    }

    /** @param accountId from the SecurityContext, never from LLM arguments (SPEC §4.3 rule 2) */
    public GuardrailDecision evaluate(AccountId accountId, ActionRequest request) {
        return chain.evaluate(request, contexts.build(accountId, request));
    }
}
