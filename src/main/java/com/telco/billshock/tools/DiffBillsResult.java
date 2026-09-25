package com.telco.billshock.tools;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

import com.telco.billshock.analysis.BillDiff;
import com.telco.billshock.analysis.BillDiff.Verdict;

/**
 * The {@code diffBills} result. The orchestrator's pre-fetch uses the same shape, so the model
 * sees one format (agent.md §4).
 *
 * @param note a fixed instruction for the verdict (never customer data)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DiffBillsResult(String status, String billPeriod, String verdict, Amount currentTotal,
        Amount baselineTotal, int baselineBillCount, Amount totalExcess, String excessPercent, List<Cause> causes,
        List<Finding> findings, String note) implements ToolResult {

    public record Cause(String group, Amount amountExclGst, Amount gst, Amount amountInclGst, List<Long> lineItemIds) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Finding(String type, List<Long> lineItemIds, String subscriptionId) {
    }

    public static DiffBillsResult from(BillDiff diff) {
        List<Cause> causes = diff.causes()
            .stream()
            .map(c -> new Cause(c.group().name(), Amount.exclGst(c.amountExclGst()), Amount.gst(c.gstAmount()),
                    Amount.inclGst(c.amountInclGst()), c.lineItemIds()))
            .toList();
        List<Finding> findings = diff.findings()
            .stream()
            .map(f -> new Finding(f.type().name(), f.lineItemIds(), f.subscriptionId()))
            .toList();
        return new DiffBillsResult("OK", diff.billPeriod().toString(), diff.verdict().name(),
                Amount.inclGst(diff.currentTotal()), Amount.inclGst(diff.baselineTotal()), diff.baselineBillCount(),
                Amount.inclGst(diff.totalExcess()),
                diff.excessPercent() == null ? null : diff.excessPercent().toPlainString(), causes, findings,
                note(diff.verdict()));
    }

    private static String note(Verdict verdict) {
        return switch (verdict) {
            case MEANINGFUL_INCREASE -> "The increase is meaningful. Explain the causes, largest first, by their"
                    + " amountInclGst display.";
            case NORMAL -> "This bill is in line with the recent bills. Say so; do not present causes or suggest"
                    + " that anything is wrong unless a finding is listed.";
            case INSUFFICIENT_HISTORY -> "There is no earlier bill to compare with, so no cause can be named.";
        };
    }
}
