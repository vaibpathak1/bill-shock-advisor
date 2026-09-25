package com.telco.billshock.analysis;

import com.telco.billshock.analysis.PlanSimulation.AddOnOption;
import com.telco.billshock.analysis.PlanSimulation.NotSimulatableReason;
import com.telco.billshock.analysis.PlanSimulation.PlanChange;
import com.telco.billshock.analysis.PlanSimulation.PlanCost;
import com.telco.billshock.analysis.PlanSimulation.PlanOption;
import com.telco.billshock.analysis.PlanSimulation.Status;
import com.telco.billshock.analysis.UsageRater.BandUsage;
import com.telco.billshock.analysis.UsageRater.PeriodUsage;
import com.telco.billshock.analysis.UsageRater.Rating;
import com.telco.billshock.bss.BillingReadModel;
import com.telco.billshock.bss.BillingReadModel.BillSummary;
import com.telco.billshock.bss.BillingReadModel.LineItem;
import com.telco.billshock.bss.BillingReadModel.RoamingUsage;
import com.telco.billshock.bss.BillingReadModel.UsagePeriodSummary;
import com.telco.billshock.bss.CatalogReadModel;
import com.telco.billshock.bss.CatalogReadModel.AddOn;
import com.telco.billshock.bss.CatalogReadModel.Band;
import com.telco.billshock.bss.CatalogReadModel.Plan;
import com.telco.billshock.bss.ProductInventoryGateway;
import com.telco.billshock.bss.ProductInventoryGateway.OrderAction;
import com.telco.billshock.bss.ProductInventoryGateway.ProductOrder;
import com.telco.billshock.bss.ProductInventoryGateway.ProductType;
import com.telco.billshock.domain.AccountId;
import com.telco.billshock.domain.BillPeriod;
import com.telco.billshock.domain.GstCalculator;
import com.telco.billshock.domain.Money;
import com.telco.billshock.domain.SupplyType;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Re-rates a period's actual usage on every catalogue plan and on the current plan with each
 * add-on, and returns the cheaper options (SPEC §4.4 {@code simulatePlans}; Q-21;
 * deterministic-core.md §3). Deterministic, no LLM (ADR-003).
 */
@Service
public class PlanSimulator {

    private static final String COMPLETED = "COMPLETED";

    private final BillingReadModel billing;
    private final CatalogReadModel catalog;
    private final ProductInventoryGateway inventory;
    private final GstCalculator gst;
    private final int maxPlanOptions;

    public PlanSimulator(BillingReadModel billing, CatalogReadModel catalog, ProductInventoryGateway inventory,
            GstCalculator gst, AnalysisProperties properties) {
        this.billing = billing;
        this.catalog = catalog;
        this.inventory = inventory;
        this.gst = gst;
        this.maxPlanOptions = properties.simulator().maxPlanOptions();
    }

    /** @return empty if the account has no bill for {@code billPeriod} */
    public Optional<PlanSimulation> simulate(AccountId accountId, BillPeriod billPeriod) {
        return billing.bill(accountId, billPeriod).map(this::simulate);
    }

    private PlanSimulation simulate(BillSummary bill) {
        AccountId accountId = bill.accountId();
        List<ProductOrder> planChanges = inventory.orders(accountId)
            .stream()
            .filter(o -> o.action() == OrderAction.PLAN_CHANGE && COMPLETED.equals(o.status()))
            .sorted(Comparator.comparing(ProductOrder::effectiveDate))
            .toList();

        Optional<PlanChange> mixed = planChangeWithin(bill, planChanges);
        if (mixed.isPresent()) {
            return new PlanSimulation(accountId, bill.billPeriod(), Status.RECENT_PLAN_CHANGE, bill.supplyType(), null,
                    List.of(), null, mixed.get(), null);
        }

        Optional<UsagePeriodSummary> usageRow = billing.usage(accountId, bill.billPeriod())
            .stream()
            .filter(u -> u.usagePeriod().equals(bill.billPeriod()))
            .findFirst();
        if (usageRow.isEmpty()) {
            return notSimulatable(bill, NotSimulatableReason.NO_USAGE);
        }
        Optional<PeriodUsage> usage = periodUsage(usageRow.get(), billing.roamingUsage(accountId, bill.billPeriod())
            .stream()
            .filter(r -> r.usagePeriod().equals(bill.billPeriod()))
            .toList());
        if (usage.isEmpty()) {
            return notSimulatable(bill, NotSimulatableReason.UNKNOWN_ROAMING_COUNTRY);
        }

        LocalDate ratingDate = bill.periodEnd();
        List<Plan> plans = catalog.plans(ratingDate);
        String currentCode = planOfPeriod(accountId, bill, planChanges);
        Optional<Plan> currentPlan = plans.stream().filter(p -> p.code().equals(currentCode)).findFirst();
        if (currentPlan.isEmpty()) {
            return notSimulatable(bill, NotSimulatableReason.CURRENT_PLAN_UNKNOWN);
        }
        Optional<Rating> currentRating = UsageRater.rate(currentPlan.get(), null, usage.get());
        if (currentRating.isEmpty()) {
            return notSimulatable(bill, NotSimulatableReason.CURRENT_PLAN_NOT_RATABLE);
        }
        if (currentRating.get().needsDailyUsage()) {
            return notSimulatable(bill, NotSimulatableReason.DAY_BASED_TARIFF);
        }
        Money currentCost = currentRating.get().cost();
        Money currentTotal = inclGst(currentCost, bill.supplyType());
        PlanCost current = new PlanCost(currentPlan.get().code(), currentPlan.get().name(), currentCost, currentTotal);

        List<PlanOption> cheaperPlans = new ArrayList<>();
        for (Plan plan : plans) {
            if (plan.code().equals(currentCode)) {
                continue;
            }
            UsageRater.rate(plan, null, usage.get()).filter(r -> !r.needsDailyUsage()).ifPresent(rating -> {
                Money saving = currentCost.minus(rating.cost());
                if (saving.isPositive()) {
                    Money total = inclGst(rating.cost(), bill.supplyType());
                    cheaperPlans.add(new PlanOption(plan.code(), plan.name(), plan.monthlyRental(), rating.cost(),
                            total, saving, currentTotal.minus(total)));
                }
            });
        }
        List<PlanOption> ranked = cheaperPlans.stream()
            .sorted(Comparator.comparing(PlanOption::savingExclGst)
                .reversed()
                .thenComparing(PlanOption::monthlyRental)
                .thenComparing(PlanOption::planCode))
            .limit(maxPlanOptions)
            .toList();

        AddOnOption bestAddOn = catalog.addOns(ratingDate)
            .stream()
            .filter(a -> !a.isRoamingPack() || UsageRater.packApplies(a, usage.get()))
            .map(a -> addOnOption(a, currentPlan.get(), usage.get(), currentCost, currentTotal, bill.supplyType()))
            .flatMap(Optional::stream)
            .min(Comparator.comparing(AddOnOption::savingExclGst)
                .reversed()
                .thenComparing(AddOnOption::price)
                .thenComparing(AddOnOption::addOnCode))
            .orElse(null);

        return new PlanSimulation(accountId, bill.billPeriod(), Status.SIMULATED, bill.supplyType(), current, ranked,
                bestAddOn, null, null);
    }

    private Optional<AddOnOption> addOnOption(AddOn addOn, Plan plan, PeriodUsage usage, Money currentCost,
            Money currentTotal, SupplyType supplyType) {
        return UsageRater.rate(plan, addOn, usage)
            .filter(r -> !r.needsDailyUsage())
            .filter(r -> currentCost.minus(r.cost()).isPositive())
            .map(r -> {
                Money total = inclGst(r.cost(), supplyType);
                return new AddOnOption(addOn.code(), addOn.name(), addOn.price(), r.cost(), total,
                        currentCost.minus(r.cost()), currentTotal.minus(total));
            });
    }

    /**
     * Q-27: a period is mixed if a completed plan change took effect after its first day and
     * on or before its last day. Without such an order, proration lines on the bill are the
     * evidence (A-91).
     */
    private Optional<PlanChange> planChangeWithin(BillSummary bill, List<ProductOrder> planChanges) {
        Optional<PlanChange> fromOrders = planChanges.stream()
            .filter(o -> o.effectiveDate().isAfter(bill.periodStart()) && !o.effectiveDate().isAfter(bill.periodEnd()))
            .reduce((first, second) -> second)
            .map(o -> new PlanChange(o.effectiveDate(), o.fromCode(), o.toCode()));
        if (fromOrders.isPresent()) {
            return fromOrders;
        }
        return billing.lineItems(bill.accountId(), bill.billPeriod())
            .stream()
            .filter(l -> "PRORATION".equals(l.category()))
            .map(LineItem::servicePeriodStart)
            .filter(Objects::nonNull)
            .filter(start -> start.isAfter(bill.periodStart()))
            .max(Comparator.naturalOrder())
            .map(start -> new PlanChange(start, null, null));
    }

    /** The active plan, unless a completed change took effect after the period (then its old plan). */
    private String planOfPeriod(AccountId accountId, BillSummary bill, List<ProductOrder> planChanges) {
        return planChanges.stream()
            .filter(o -> o.effectiveDate().isAfter(bill.periodEnd()))
            .findFirst()
            .map(ProductOrder::fromCode)
            .orElseGet(() -> inventory.activeProducts(accountId)
                .stream()
                .filter(p -> p.type() == ProductType.PLAN)
                .map(ProductInventoryGateway.ActiveProduct::code)
                .findFirst()
                .orElse(null));
    }

    /** @return empty if a roaming country has no configured band */
    private Optional<PeriodUsage> periodUsage(UsagePeriodSummary u, List<RoamingUsage> roaming) {
        Map<Band, BandUsage> byBand = new EnumMap<>(Band.class);
        for (RoamingUsage r : roaming) {
            Optional<Band> band = catalog.roamingBand(r.countryCode());
            if (band.isEmpty()) {
                return Optional.empty();
            }
            BandUsage add = new BandUsage(r.dataMb(), r.voiceMin(), BigDecimal.valueOf(r.smsCount()), r.firstDay(),
                    r.lastDay());
            byBand.merge(band.get(), add, (a, b) -> new BandUsage(a.dataMb().add(b.dataMb()),
                    a.voiceMin().add(b.voiceMin()), a.smsCount().add(b.smsCount()),
                    a.firstDay().isBefore(b.firstDay()) ? a.firstDay() : b.firstDay(),
                    a.lastDay().isAfter(b.lastDay()) ? a.lastDay() : b.lastDay()));
        }
        return Optional.of(new PeriodUsage(u.dataMb(), u.voiceMin(), BigDecimal.valueOf(u.smsCount()), u.isdMin(),
                byBand));
    }

    private Money inclGst(Money exclGst, SupplyType supplyType) {
        return exclGst.plus(gst.compute(exclGst, supplyType).total());
    }

    private static PlanSimulation notSimulatable(BillSummary bill, NotSimulatableReason reason) {
        return new PlanSimulation(bill.accountId(), bill.billPeriod(), Status.NOT_SIMULATABLE, bill.supplyType(),
                null, List.of(), null, null, reason);
    }
}
