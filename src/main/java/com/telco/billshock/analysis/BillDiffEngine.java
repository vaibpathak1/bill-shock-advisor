package com.telco.billshock.analysis;

import com.telco.billshock.analysis.BillDiff.Cause;
import com.telco.billshock.analysis.BillDiff.Finding;
import com.telco.billshock.analysis.BillDiff.FindingType;
import com.telco.billshock.analysis.BillDiff.GroupDelta;
import com.telco.billshock.analysis.BillDiff.Verdict;
import com.telco.billshock.bss.BillingReadModel;
import com.telco.billshock.bss.BillingReadModel.BillSummary;
import com.telco.billshock.bss.BillingReadModel.LineItem;
import com.telco.billshock.domain.AccountId;
import com.telco.billshock.domain.BillPeriod;
import com.telco.billshock.domain.GstCalculator;
import com.telco.billshock.domain.Money;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Compares a bill with the mean of the bills before it and explains the difference cause
 * by cause (SPEC §4.4 {@code diffBills}; deterministic-core.md §2). Deterministic, no LLM
 * (ADR-003): money is {@link Money}, HALF_EVEN, and the causes add up to the total excess
 * exactly (US-DIA-01).
 */
@Service
public class BillDiffEngine {

    public static final int DEFAULT_BASELINE_MONTHS = 3;

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final BillingReadModel billing;
    private final GstCalculator gst;
    private final AnalysisProperties.Chat chatRule;

    public BillDiffEngine(BillingReadModel billing, GstCalculator gst, AnalysisProperties properties) {
        this.billing = billing;
        this.gst = gst;
        this.chatRule = properties.chat();
    }

    public Optional<BillDiff> diff(AccountId accountId, BillPeriod billPeriod) {
        return diff(accountId, billPeriod, DEFAULT_BASELINE_MONTHS);
    }

    /** The diff of the account's latest bill (the "current" bill) against the default baseline. */
    public Optional<BillDiff> diffLatest(AccountId accountId) {
        return billing.latestBill(accountId).map(current -> diff(current, DEFAULT_BASELINE_MONTHS));
    }

    /**
     * @param baselineMonths how many billing months before {@code billPeriod} form the baseline
     * @return empty if the account has no bill for {@code billPeriod}
     */
    public Optional<BillDiff> diff(AccountId accountId, BillPeriod billPeriod, int baselineMonths) {
        if (baselineMonths < 1) {
            throw new IllegalArgumentException("baselineMonths must be at least 1: " + baselineMonths);
        }
        return billing.bill(accountId, billPeriod).map(current -> diff(current, baselineMonths));
    }

    private BillDiff diff(BillSummary current, int baselineMonths) {
        AccountId accountId = current.accountId();
        List<LineItem> currentLines = charges(billing.lineItems(accountId, current.billPeriod()));
        List<BillSummary> baselineBills = billing.billHistory(accountId,
                current.billPeriod().minusMonths(baselineMonths), current.billPeriod().minusMonths(1));
        List<List<LineItem>> baselineLines = baselineBills.stream()
            .map(b -> charges(billing.lineItems(accountId, b.billPeriod())))
            .toList();
        List<Finding> findings = findings(currentLines, baselineLines);

        if (baselineBills.isEmpty()) {
            return new BillDiff(accountId, current.billPeriod(), current.supplyType(), current.subtotal(),
                    current.total(), 0, null, null, null, null, null, null, Verdict.INSUFFICIENT_HISTORY, List.of(),
                    List.of(), Money.ZERO, Money.ZERO, findings);
        }

        Money baselineSubtotal = Money.average(baselineBills.stream().map(BillSummary::subtotal).toList());
        Money baselineTotal = Money.average(baselineBills.stream().map(BillSummary::total).toList());
        Money subtotalExcess = current.subtotal().minus(baselineSubtotal);
        Money totalExcess = current.total().minus(baselineTotal);
        List<GroupDelta> groupDeltas = groupDeltas(currentLines, baselineLines);
        Verdict verdict = isMeaningfulIncrease(totalExcess, baselineTotal) ? Verdict.MEANINGFUL_INCREASE
                : Verdict.NORMAL;

        List<Cause> causes = List.of();
        Money adjustmentExclGst = Money.ZERO;
        Money adjustmentGst = Money.ZERO;
        if (verdict == Verdict.MEANINGFUL_INCREASE) {
            List<GroupDelta> ranked = groupDeltas.stream()
                .filter(d -> !d.delta().isZero())
                .sorted(Comparator.comparing(GroupDelta::delta).reversed().thenComparing(GroupDelta::group))
                .toList();
            if (!ranked.isEmpty()) {
                Money sumDeltas = ranked.stream().map(GroupDelta::delta).reduce(Money.ZERO, Money::plus);
                adjustmentExclGst = subtotalExcess.minus(sumDeltas);
                causes = new ArrayList<>();
                for (int i = 0; i < ranked.size(); i++) {
                    GroupDelta d = ranked.get(i);
                    Money exclGst = i == 0 ? d.delta().plus(adjustmentExclGst) : d.delta();
                    Money gstAmount = gst.compute(exclGst, current.supplyType()).total();
                    causes.add(new Cause(d.group(), exclGst, gstAmount, exclGst.plus(gstAmount),
                            lineIds(currentLines, d.group())));
                }
                Money sumInclGst = causes.stream().map(Cause::amountInclGst).reduce(Money.ZERO, Money::plus);
                adjustmentGst = totalExcess.minus(sumInclGst);
                Cause largest = causes.getFirst();
                causes.set(0, new Cause(largest.group(), largest.amountExclGst(),
                        largest.gstAmount().plus(adjustmentGst), largest.amountInclGst().plus(adjustmentGst),
                        largest.lineItemIds()));
            }
        }

        return new BillDiff(accountId, current.billPeriod(), current.supplyType(), current.subtotal(),
                current.total(), baselineBills.size(), baselineSubtotal, baselineTotal, subtotalExcess, totalExcess,
                percent(totalExcess, baselineTotal), ratio(current.total(), baselineTotal), verdict, groupDeltas,
                causes, adjustmentExclGst, adjustmentGst, findings);
    }

    /** Q-22: excess ≥ ₹min and ≥ min% of the baseline, compared exactly (no rounded percentage). */
    private boolean isMeaningfulIncrease(Money totalExcess, Money baselineTotal) {
        boolean amountTest = totalExcess.amount().compareTo(chatRule.minExcessInr()) >= 0;
        boolean shareTest = totalExcess.amount()
            .multiply(HUNDRED)
            .compareTo(chatRule.minExcessPct().multiply(baselineTotal.amount())) >= 0;
        return amountTest && shareTest;
    }

    private static List<GroupDelta> groupDeltas(List<LineItem> currentLines, List<List<LineItem>> baselineLines) {
        Map<CauseGroup, Money> current = sumByGroup(currentLines);
        List<Map<CauseGroup, Money>> baselines = baselineLines.stream().map(BillDiffEngine::sumByGroup).toList();
        Set<CauseGroup> groups = new TreeSet<>(current.keySet());
        baselines.forEach(b -> groups.addAll(b.keySet()));
        return groups.stream().map(group -> {
            Money now = current.getOrDefault(group, Money.ZERO);
            Money before = Money.average(baselines.stream().map(b -> b.getOrDefault(group, Money.ZERO)).toList());
            return new GroupDelta(group, now, before, now.minus(before));
        }).toList();
    }

    private static Map<CauseGroup, Money> sumByGroup(List<LineItem> lines) {
        Map<CauseGroup, Money> sums = new EnumMap<>(CauseGroup.class);
        for (LineItem line : lines) {
            CauseGroup.of(line.category()).ifPresent(g -> sums.merge(g, line.amount(), Money::plus));
        }
        return sums;
    }

    private static List<Finding> findings(List<LineItem> currentLines, List<List<LineItem>> baselineLines) {
        List<Finding> findings = new ArrayList<>();
        DuplicateChargeDetector.duplicateGroups(currentLines)
            .forEach(group -> findings.add(new Finding(FindingType.DUPLICATE_CHARGE,
                    group.stream().map(LineItem::lineItemId).toList(), null)));

        Set<String> knownSubscriptions = baselineLines.stream()
            .flatMap(List::stream)
            .map(LineItem::subscriptionId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
        Map<String, List<Long>> newSubscriptions = new LinkedHashMap<>();
        if (!baselineLines.isEmpty()) {
            currentLines.stream()
                .filter(l -> l.subscriptionId() != null && !knownSubscriptions.contains(l.subscriptionId()))
                .forEach(l -> newSubscriptions.computeIfAbsent(l.subscriptionId(), k -> new ArrayList<>())
                    .add(l.lineItemId()));
        }
        newSubscriptions.forEach((subscription, ids) -> findings
            .add(new Finding(FindingType.NEW_SUBSCRIPTION_CHARGE, ids, subscription)));

        List<Long> proration = currentLines.stream()
            .filter(l -> "PRORATION".equals(l.category()))
            .map(LineItem::lineItemId)
            .toList();
        if (!proration.isEmpty()) {
            findings.add(new Finding(FindingType.PLAN_CHANGE_PRORATION, proration, null));
        }
        return findings;
    }

    private static List<LineItem> charges(List<LineItem> lines) {
        return lines.stream().filter(l -> !"TAX".equals(l.category())).toList();
    }

    private static List<Long> lineIds(List<LineItem> lines, CauseGroup group) {
        return lines.stream()
            .filter(l -> CauseGroup.of(l.category()).orElse(null) == group)
            .map(LineItem::lineItemId)
            .toList();
    }

    private static BigDecimal percent(Money excess, Money baseline) {
        return baseline.isZero() ? null
                : excess.amount().multiply(HUNDRED).divide(baseline.amount(), 1, RoundingMode.HALF_EVEN);
    }

    private static BigDecimal ratio(Money current, Money baseline) {
        return baseline.isZero() ? null : current.amount().divide(baseline.amount(), 3, RoundingMode.HALF_EVEN);
    }
}
