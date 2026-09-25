package com.telco.billshock.actions.guardrail;

import com.telco.billshock.bss.BillingReadModel.BillSummary;
import com.telco.billshock.bss.BillingReadModel.LineItem;
import com.telco.billshock.bss.CatalogReadModel.AddOn;
import com.telco.billshock.bss.CatalogReadModel.Plan;
import com.telco.billshock.bss.ProductInventoryGateway.ActiveProduct;
import com.telco.billshock.domain.AccountId;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The facts the guardrails decide on. **Always built server-side by
 * {@link GuardrailContextFactory} from the read models and BSS gateways, never from LLM tool
 * arguments** (deterministic-core.md §4.1). Internal to the {@code actions} module.
 *
 * @param bill the bill the action concerns: the one holding the cited line items, else the latest; empty if the
 *        account has no bill
 * @param bills the account's local bill history, oldest first
 * @param lineItems every line item of {@code bills}, by id; ids of other accounts are never in it
 * @param duplicateLineItemIds lines on {@code bill} that belong to a duplicate group, the original included
 * @param priorCredit a credit on {@code bill} or in the configured months before it
 * @param products the account's active products (TMF622), with opt-in evidence
 */
public record GuardrailContext(AccountId accountId, int autonomyLevel, Optional<BillSummary> bill,
        List<BillSummary> bills, Map<Long, LineItem> lineItems, Set<Long> duplicateLineItemIds, boolean priorCredit,
        List<ActiveProduct> products, List<Plan> plans, List<AddOn> addOns) {

    public GuardrailContext {
        bills = List.copyOf(bills);
        lineItems = Map.copyOf(lineItems);
        duplicateLineItemIds = Set.copyOf(duplicateLineItemIds);
        products = List.copyOf(products);
        plans = List.copyOf(plans);
        addOns = List.copyOf(addOns);
    }

    public Optional<BillSummary> billById(long billId) {
        return bills.stream().filter(b -> b.billId() == billId).findFirst();
    }

    public Optional<ActiveProduct> product(String subscriptionId) {
        return products.stream().filter(p -> p.subscriptionId().equals(subscriptionId)).findFirst();
    }
}
