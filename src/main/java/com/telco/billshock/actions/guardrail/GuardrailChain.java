package com.telco.billshock.actions.guardrail;

import com.telco.billshock.actions.ActionRequest;
import com.telco.billshock.actions.GuardrailDecision;
import com.telco.billshock.actions.GuardrailProperties;
import com.telco.billshock.domain.GstCalculator;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * The guardrail chain (deterministic-core.md §4.4), in order. It stops at the first
 * {@code REJECT} or {@code ESCALATE}. Runs when a proposal is created and again at
 * confirmation (ADR-004, 6a).
 */
@Component
public class GuardrailChain {

    private final List<GuardrailCheck> checks;

    public GuardrailChain(GuardrailProperties properties, GstCalculator gst) {
        MoneyOutCap cap = new MoneyOutCap(properties.moneyOutEscalateAboveInr());
        this.checks = List.of(new AutonomyCheck(), new ReferenceCheck(),
                new GoodwillCreditCheck(properties.goodwill(), cap, gst), new VasCheck(cap, gst), new PlanChangeCheck(),
                new DisputeCheck(gst), new EscalationCheck());
    }

    public GuardrailDecision evaluate(ActionRequest request, GuardrailContext context) {
        DecisionBuilder decision = new DecisionBuilder();
        for (GuardrailCheck check : checks) {
            check.apply(request, context, decision);
            if (decision.isTerminal()) {
                break;
            }
        }
        return decision.build(context.autonomyLevel());
    }
}
