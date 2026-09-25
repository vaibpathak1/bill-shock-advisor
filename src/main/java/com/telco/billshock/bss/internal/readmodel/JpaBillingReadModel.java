package com.telco.billshock.bss.internal.readmodel;

import com.telco.billshock.bss.BillingReadModel;
import com.telco.billshock.domain.AccountId;
import com.telco.billshock.domain.BillPeriod;
import com.telco.billshock.domain.Money;
import com.telco.billshock.domain.SupplyType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * {@link BillingReadModel} over the local replica tables. Read-only transactions, so the
 * routing DataSource can later send them to the replica (data-architecture.md §6, 3b).
 */
@Service
@Transactional(readOnly = true)
class JpaBillingReadModel implements BillingReadModel {

    private final BillRepository bills;
    private final LineItemRepository lineItems;
    private final UsagePeriodRepository usagePeriods;
    private final UsagePeriodRoamingRepository roaming;

    JpaBillingReadModel(BillRepository bills, LineItemRepository lineItems, UsagePeriodRepository usagePeriods,
            UsagePeriodRoamingRepository roaming) {
        this.bills = bills;
        this.lineItems = lineItems;
        this.usagePeriods = usagePeriods;
        this.roaming = roaming;
    }

    @Override
    public List<BillSummary> billHistory(AccountId accountId, BillPeriod from, BillPeriod to) {
        return bills.findByAccountIdAndBillPeriodBetweenOrderByBillPeriod(accountId.value(), from.firstDay(), to.firstDay())
            .stream()
            .map(JpaBillingReadModel::toSummary)
            .toList();
    }

    @Override
    public Optional<BillSummary> bill(AccountId accountId, BillPeriod billPeriod) {
        return bills.findByAccountIdAndBillPeriod(accountId.value(), billPeriod.firstDay())
            .map(JpaBillingReadModel::toSummary);
    }

    @Override
    public Optional<BillSummary> latestBill(AccountId accountId) {
        return bills.findFirstByAccountIdOrderByBillPeriodDesc(accountId.value()).map(JpaBillingReadModel::toSummary);
    }

    @Override
    public List<LineItem> lineItems(AccountId accountId, BillPeriod billPeriod) {
        // Resolve the bill through the account first: the account is the authorisation key,
        // and (bill_period, bill_id) then hits one partition and the (bill_id, category) index.
        return bills.findByAccountIdAndBillPeriod(accountId.value(), billPeriod.firstDay())
            .map(bill -> lineItems.findByBillPeriodAndBillIdOrderByLineItemId(bill.getBillPeriod(), bill.getBillId())
                .stream()
                .map(JpaBillingReadModel::toLineItem)
                .toList())
            .orElse(List.of());
    }

    @Override
    public List<UsagePeriodSummary> usage(AccountId accountId, BillPeriod billedPeriod) {
        return usagePeriods.findByAccountIdAndBilledPeriodOrderByUsagePeriod(accountId.value(), billedPeriod.firstDay())
            .stream()
            .map(JpaBillingReadModel::toUsage)
            .toList();
    }

    @Override
    public List<RoamingUsage> roamingUsage(AccountId accountId, BillPeriod billedPeriod) {
        return roaming
            .findByAccountIdAndBilledPeriodOrderByUsagePeriodAscCountryCodeAsc(accountId.value(), billedPeriod.firstDay())
            .stream()
            .map(r -> new RoamingUsage(new BillPeriod(r.getBilledPeriod()), new BillPeriod(r.getUsagePeriod()),
                    r.getCountryCode(), r.getFirstDay(), r.getLastDay(), r.getDataMb(), r.getVoiceMin(),
                    r.getSmsCount(), Money.of(r.getCharge())))
            .toList();
    }

    private static BillSummary toSummary(BillEntity b) {
        return new BillSummary(b.getBillId(), AccountId.of(b.getAccountId()), new BillPeriod(b.getBillPeriod()),
                b.getPeriodStart(), b.getPeriodEnd(), b.getBillDate(), b.getPlaceOfSupply(),
                SupplyType.valueOf(b.getSupplyType()), Money.of(b.getSubtotal()), Money.of(b.getTaxTotal()),
                Money.of(b.getTotal()));
    }

    private static LineItem toLineItem(LineItemEntity l) {
        return new LineItem(l.getLineItemId(), l.getBillId(), new BillPeriod(l.getBillPeriod()), l.getCategory(),
                l.getDescription(), period(l.getUsagePeriod()), l.getServicePeriodStart(), l.getServicePeriodEnd(),
                l.getSubscriptionId(), l.getQuantity(), l.getUnit(), Money.of(l.getAmount()), l.getTaxComponent(),
                l.getTaxRate(), l.getExternalRef());
    }

    private static UsagePeriodSummary toUsage(UsagePeriodEntity u) {
        return new UsagePeriodSummary(AccountId.of(u.getAccountId()), new BillPeriod(u.getBilledPeriod()),
                new BillPeriod(u.getUsagePeriod()), u.getDataMb(), Money.of(u.getDataCharge()), u.getVoiceMin(),
                u.getIsdMin(), Money.of(u.getVoiceCharge()), u.getSmsCount(), Money.of(u.getSmsCharge()), u.getRoamingDataMb(),
                u.getRoamingVoiceMin(), u.getRoamingSmsCount(), Money.of(u.getRoamingCharge()));
    }

    private static BillPeriod period(LocalDate firstDay) {
        return firstDay == null ? null : new BillPeriod(firstDay);
    }
}
