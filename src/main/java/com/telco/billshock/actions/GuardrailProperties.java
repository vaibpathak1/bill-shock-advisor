package com.telco.billshock.actions;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;

/**
 * Guardrail thresholds (SPEC §4.5; deterministic-core.md §4.5). Never shown to the LLM.
 * All amounts are GST-inclusive (Q-20), and every limit is inclusive (A-84).
 *
 * @param autonomyLevel 0 = explanations only, 1 = every action needs confirmation, 2 = policy
 *        auto-approval (SPEC §8.2); fixed at 1 in the MVP slice
 * @param moneyOutEscalateAboveInr ₹2,000: any action that pays money out (goodwill credit, VAS
 *        refund) above this GST-inclusive amount is escalated. No money-out action is uncapped
 *        (gate review 4a, item 4)
 */
@Validated
@ConfigurationProperties("billshock.guardrails")
public record GuardrailProperties(@Min(0) @Max(2) int autonomyLevel,
        @NotNull @DecimalMin("0.00") BigDecimal moneyOutEscalateAboveInr, @Valid @NotNull Goodwill goodwill,
        @Min(0) int historyMonths) {

    /**
     * @param autoApprovalMaxInr ₹500: above it, a supervisor must approve
     * @param autoApprovalMaxBillSharePct 15% of the bill: above it, a supervisor must approve
     * @param priorCreditLookbackMonths 6: any credit in this window needs a supervisor
     */
    public record Goodwill(@NotNull @DecimalMin("0.00") BigDecimal autoApprovalMaxInr,
            @NotNull @DecimalMin("0.00") BigDecimal autoApprovalMaxBillSharePct, @Min(0) int priorCreditLookbackMonths) {
    }
}
