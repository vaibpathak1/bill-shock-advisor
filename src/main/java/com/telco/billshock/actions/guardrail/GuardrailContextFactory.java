package com.telco.billshock.actions.guardrail;

import com.telco.billshock.actions.ActionRequest;
import com.telco.billshock.actions.GuardrailProperties;
import com.telco.billshock.analysis.DuplicateChargeDetector;
import com.telco.billshock.bss.BillingReadModel;
import com.telco.billshock.bss.BillingReadModel.BillSummary;
import com.telco.billshock.bss.BillingReadModel.LineItem;
import com.telco.billshock.bss.CatalogReadModel;
import com.telco.billshock.bss.ProductInventoryGateway;
import com.telco.billshock.domain.AccountId;
import com.telco.billshock.domain.BillPeriod;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Builds the {@link GuardrailContext} for one request, from the read models and BSS gateways
 * only, under the caller's account (deterministic-core.md §4.1, §4.2). The request supplies
 * ids to look up, never facts: an id that is not found under the account is simply absent
 * from the context, and the reference check rejects it.
 */
@Component
public class GuardrailContextFactory {

    private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");

    private final BillingReadModel billing;
    private final CatalogReadModel catalog;
    private final ProductInventoryGateway inventory;
    private final GuardrailProperties properties;
    private final Clock clock;

    @Autowired
    public GuardrailContextFactory(BillingReadModel billing, CatalogReadModel catalog,
            ProductInventoryGateway inventory, GuardrailProperties properties, ObjectProvider<Clock> clock) {
        this(billing, catalog, inventory, properties, clock.getIfAvailable(() -> Clock.system(INDIA)));
    }

    public GuardrailContextFactory(BillingReadModel billing, CatalogReadModel catalog,
            ProductInventoryGateway inventory, GuardrailProperties properties, Clock clock) {
        this.billing = billing;
        this.catalog = catalog;
        this.inventory = inventory;
        this.properties = properties;
        this.clock = clock;
    }

    /** @param accountId from the SecurityContext, never from the LLM (SPEC §4.3 rule 2) */
    public GuardrailContext build(AccountId accountId, ActionRequest request) {
        Optional<BillSummary> latest = billing.latestBill(accountId);
        List<BillSummary> bills = latest
            .map(l -> billing.billHistory(accountId, l.billPeriod().minusMonths(properties.historyMonths()),
                    l.billPeriod()))
            .orElse(List.of());
        Map<Long, LineItem> lineItems = new LinkedHashMap<>();
        for (BillSummary bill : bills) {
            billing.lineItems(accountId, bill.billPeriod()).forEach(l -> lineItems.put(l.lineItemId(), l));
        }

        Optional<BillSummary> bill = billInQuestion(request, lineItems, bills).or(() -> latest);
        Set<Long> duplicates = bill
            .map(b -> DuplicateChargeDetector.involvedIds(lineItems.values()
                .stream()
                .filter(l -> l.billId() == b.billId())
                .toList()))
            .orElse(Set.of());
        boolean priorCredit = bill.map(b -> hasPriorCredit(b.billPeriod(), bills, lineItems)).orElse(false);

        LocalDate today = LocalDate.now(clock);
        return new GuardrailContext(accountId, properties.autonomyLevel(), bill, bills, lineItems, duplicates,
                priorCredit, inventory.activeProducts(accountId), catalog.plans(today), catalog.addOns(today));
    }

    /** The one bill that holds every cited line item, if there is one. */
    private static Optional<BillSummary> billInQuestion(ActionRequest request, Map<Long, LineItem> lineItems,
            List<BillSummary> bills) {
        Set<Long> billIds = citedLineItemIds(request).stream()
            .map(lineItems::get)
            .filter(Objects::nonNull)
            .map(LineItem::billId)
            .collect(Collectors.toSet());
        if (billIds.size() != 1) {
            return Optional.empty();
        }
        long billId = billIds.iterator().next();
        return bills.stream().filter(b -> b.billId() == billId).findFirst();
    }

    /** CREDIT lines, or negative ADJUSTMENT lines, on the bill or in the lookback months before it (A-85). */
    private boolean hasPriorCredit(BillPeriod period, List<BillSummary> bills, Map<Long, LineItem> lineItems) {
        BillPeriod from = period.minusMonths(properties.goodwill().priorCreditLookbackMonths());
        Set<Long> window = bills.stream()
            .filter(b -> b.billPeriod().compareTo(from) >= 0 && b.billPeriod().compareTo(period) <= 0)
            .map(BillSummary::billId)
            .collect(Collectors.toSet());
        return lineItems.values()
            .stream()
            .filter(l -> window.contains(l.billId()))
            .anyMatch(l -> "CREDIT".equals(l.category())
                    || "ADJUSTMENT".equals(l.category()) && l.amount().isNegative());
    }

    static List<Long> citedLineItemIds(ActionRequest request) {
        return switch (request) {
            case ActionRequest.GoodwillCredit credit -> credit.lineItemIds();
            case ActionRequest.Dispute dispute -> dispute.lineItemIds();
            default -> List.of();
        };
    }
}
