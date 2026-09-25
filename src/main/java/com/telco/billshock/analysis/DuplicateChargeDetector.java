package com.telco.billshock.analysis;

import com.telco.billshock.bss.BillingReadModel.LineItem;
import com.telco.billshock.domain.Money;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Finds the same charge billed twice on one bill (deterministic-core.md §2.6): non-TAX lines
 * with the same category, amount, service period, subscription and a non-null external
 * reference. Used by {@link BillDiffEngine} and by the goodwill guardrail (US-RES-03).
 */
public final class DuplicateChargeDetector {

    private DuplicateChargeDetector() {
    }

    /** Each group of identical charges, lowest line id first. Only groups of two or more. */
    public static List<List<LineItem>> duplicateGroups(List<LineItem> lineItems) {
        record Key(String category, Money amount, LocalDate start, LocalDate end, String subscriptionId,
                String externalRef) {
        }
        Map<Key, List<LineItem>> groups = lineItems.stream()
            .filter(l -> !"TAX".equals(l.category()) && l.externalRef() != null)
            .sorted(Comparator.comparingLong(LineItem::lineItemId))
            .collect(Collectors.groupingBy(l -> new Key(l.category(), l.amount(), l.servicePeriodStart(),
                    l.servicePeriodEnd(), l.subscriptionId(), l.externalRef()), LinkedHashMap::new,
                    Collectors.toList()));
        return groups.values().stream().filter(g -> g.size() > 1).toList();
    }

    /** The ids of the extra copies (every line of a group except the first). */
    public static Set<Long> duplicateIds(List<LineItem> lineItems) {
        return duplicateGroups(lineItems).stream()
            .flatMap(g -> g.stream().skip(1))
            .map(LineItem::lineItemId)
            .collect(Collectors.toUnmodifiableSet());
    }

    /** The ids of every line in a duplicate group, the original included. */
    public static Set<Long> involvedIds(List<LineItem> lineItems) {
        return duplicateGroups(lineItems).stream()
            .flatMap(List::stream)
            .map(LineItem::lineItemId)
            .collect(Collectors.toUnmodifiableSet());
    }
}
