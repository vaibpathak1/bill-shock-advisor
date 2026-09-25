package com.telco.billshock.support;

import com.telco.billshock.bss.BillingReadModel;
import com.telco.billshock.bss.CatalogReadModel;
import com.telco.billshock.bss.ProductInventoryGateway;
import com.telco.billshock.domain.AccountId;
import com.telco.billshock.domain.BillPeriod;
import com.telco.billshock.domain.GstCalculator;
import com.telco.billshock.domain.GstCalculator.TaxLine;
import com.telco.billshock.domain.Money;
import com.telco.billshock.domain.SupplyType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * In-memory copies of the seed data (seed-scenarios.md §3–§6) for unit tests without a
 * database: the six accounts' bills March–September 2026 with line items, usage, the
 * catalogue and the TMF622 fixture. Tax lines are computed with {@link GstCalculator}; the
 * tests assert the resulting totals against the documented values, and the ITs check the
 * same expectations against the real seed.
 */
public final class SeedScenarioFixtures {

    public static final BillPeriod SEPTEMBER = BillPeriod.of(2026, 9);
    public static final GstCalculator GST = new GstCalculator(new BigDecimal("18.00"));

    private static final int[][] DATA_GB = {
            { 112, 118, 110, 121, 115, 119, 114 }, // 1001
            { 31, 33, 35, 36, 38, 43, 58 }, // 1002
            { 55, 58, 61, 57, 63, 60, 62 }, // 1003
            { 36, 37, 38, 36, 39, 37, 39 }, // 1004
            { 86, 90, 88, 93, 91, 95, 94 }, // 1005
            { 62, 65, 68, 64, 66, 70, 67 } }; // 1006
    private static final int[] ISD_MIN_1006 = { 2, 3, 1, 4, 2, 3, 4 };

    private SeedScenarioFixtures() {
    }

    // ---------------------------------------------------------------- billing

    /** A mutable in-memory {@link BillingReadModel}; tests may add bills and line items. */
    public static final class InMemoryBilling implements BillingReadModel {

        final List<BillSummary> bills = new ArrayList<>();
        final Map<Long, List<LineItem>> lines = new HashMap<>();
        final List<UsagePeriodSummary> usage = new ArrayList<>();
        final Map<Long, List<RoamingUsage>> roaming = new HashMap<>();

        @Override
        public List<BillSummary> billHistory(AccountId accountId, BillPeriod from, BillPeriod to) {
            return bills.stream()
                .filter(b -> b.accountId().equals(accountId) && b.billPeriod().compareTo(from) >= 0
                        && b.billPeriod().compareTo(to) <= 0)
                .sorted(Comparator.comparing(BillSummary::billPeriod))
                .toList();
        }

        @Override
        public Optional<BillSummary> bill(AccountId accountId, BillPeriod billPeriod) {
            return bills.stream()
                .filter(b -> b.accountId().equals(accountId) && b.billPeriod().equals(billPeriod))
                .findFirst();
        }

        @Override
        public Optional<BillSummary> latestBill(AccountId accountId) {
            return bills.stream()
                .filter(b -> b.accountId().equals(accountId))
                .max(Comparator.comparing(BillSummary::billPeriod));
        }

        @Override
        public List<LineItem> lineItems(AccountId accountId, BillPeriod billPeriod) {
            return bill(accountId, billPeriod).map(b -> lines.getOrDefault(b.billId(), List.of())).orElse(List.of());
        }

        @Override
        public List<UsagePeriodSummary> usage(AccountId accountId, BillPeriod billedPeriod) {
            return usage.stream()
                .filter(u -> u.accountId().equals(accountId) && u.billedPeriod().equals(billedPeriod))
                .toList();
        }

        @Override
        public List<RoamingUsage> roamingUsage(AccountId accountId, BillPeriod billedPeriod) {
            return roaming.getOrDefault(accountId.value(), List.of())
                .stream()
                .filter(r -> r.billedPeriod().equals(billedPeriod))
                .toList();
        }

        @Override
        public Optional<AccountSummary> account(AccountId accountId) {
            return latestBill(accountId).map(b -> new AccountSummary(accountId,
                    "+9155500" + accountId.value(), b.billDate().getDayOfMonth(), b.placeOfSupply(), "ACTIVE"));
        }

        /**
         * Adds a bill from its charge lines; the TAX lines, subtotal and totals are computed
         * with the GST rule.
         */
        public BillSummary addBill(long account, BillPeriod period, int cycleDay, SupplyType supply,
                List<Charge> charges) {
            long billId = account * 10000 + (period.firstDay().getYear() % 100) * 100L
                    + period.firstDay().getMonthValue();
            LocalDate billDate = period.firstDay().withDayOfMonth(cycleDay);
            LocalDate periodStart = billDate.minusMonths(1);
            LocalDate periodEnd = billDate.minusDays(1);
            List<LineItem> items = new ArrayList<>();
            int seq = 1;
            Money subtotal = Money.ZERO;
            for (Charge c : charges) {
                long id = billId * 100 + seq++;
                items.add(new LineItem(id, billId, period, c.category(), c.description(), null,
                        c.start() == null ? periodStart : c.start(), c.end() == null ? periodEnd : c.end(),
                        c.subscriptionId(), c.quantity(), c.unit(), c.amount(), null, null,
                        c.externalRef() == null ? "CHG-" + id : c.externalRef()));
                subtotal = subtotal.plus(c.amount());
            }
            Money tax = Money.ZERO;
            for (TaxLine t : GST.compute(subtotal, supply).lines()) {
                long id = billId * 100 + seq++;
                items.add(new LineItem(id, billId, period, "TAX", t.component() + " " + t.ratePercent() + "%", null,
                        null, null, null, null, null, t.amount(), t.component().name(), t.ratePercent(),
                        "TAX-" + id));
                tax = tax.plus(t.amount());
            }
            BillSummary bill = new BillSummary(billId, AccountId.of(account), period, periodStart, periodEnd,
                    billDate, supply == SupplyType.INTRA ? "27" : "29", supply, subtotal, tax, subtotal.plus(tax));
            bills.add(bill);
            lines.put(billId, items);
            return bill;
        }

        public void addRoaming(long account, RoamingUsage usage) {
            roaming.merge(account, List.of(usage), (a, b) -> {
                List<RoamingUsage> all = new ArrayList<>(a);
                all.addAll(b);
                return all;
            });
        }

        public void addUsage(long account, BillPeriod period, long dataMb, long isdMin, Money dataCharge,
                Money voiceCharge) {
            usage.add(new UsagePeriodSummary(AccountId.of(account), period, period, BigDecimal.valueOf(dataMb),
                    dataCharge, BigDecimal.valueOf(400), BigDecimal.valueOf(isdMin), voiceCharge, 30, Money.ZERO,
                    BigDecimal.ZERO, BigDecimal.ZERO, 0, Money.ZERO));
        }
    }

    /** A charge line; {@code null} service dates mean the bill's usage period. */
    public record Charge(String category, String description, String subscriptionId, BigDecimal quantity, String unit,
            Money amount, LocalDate start, LocalDate end, String externalRef) {

        public static Charge of(String category, String subscriptionId, String amount) {
            return new Charge(category, category + " charge", subscriptionId, BigDecimal.ONE, "EA", Money.of(amount),
                    null, null, null);
        }
    }

    /** The six seed accounts, March to September 2026. */
    public static InMemoryBilling billing() {
        InMemoryBilling b = new InMemoryBilling();
        for (int m = 0; m < 7; m++) {
            BillPeriod p = BillPeriod.of(2026, 3 + m);
            boolean sep = m == 6;

            // 1001: UAE roaming without a pack in September.
            List<Charge> c1 = new ArrayList<>(List.of(rental("SUB-1001-PLAN", "699.00")));
            if (sep) {
                LocalDate s = LocalDate.of(2026, 8, 12);
                LocalDate e = LocalDate.of(2026, 8, 18);
                c1.add(new Charge("ROAMING", "Roaming data AE", "SUB-1001-PLAN", new BigDecimal("550"), "MB",
                        Money.of("1100.00"), s, e, null));
                c1.add(new Charge("ROAMING", "Roaming voice AE", "SUB-1001-PLAN", new BigDecimal("10"), "MIN",
                        Money.of("600.00"), s, e, null));
                c1.add(new Charge("ROAMING", "Roaming SMS AE", "SUB-1001-PLAN", new BigDecimal("3"), "SMS",
                        Money.of("75.00"), s, e, null));
            }
            b.addBill(1001, p, 1, SupplyType.INTRA, c1);

            // 1002: domestic data overage in August and September.
            List<Charge> c2 = new ArrayList<>(List.of(rental("SUB-1002-PLAN", "399.00")));
            Money over2 = m == 5 ? Money.of("61.44") : sep ? Money.of("368.64") : Money.ZERO;
            if (over2.isPositive()) {
                c2.add(Charge.of("DATA", "SUB-1002-PLAN", over2.toString()));
            }
            b.addBill(1002, p, 6, SupplyType.INTER, c2);

            // 1003: a legitimate VAS every month, and Astro Daily without opt-in in September.
            List<Charge> c3 = new ArrayList<>(List.of(rental("SUB-1003-PLAN", "499.00"),
                    Charge.of("VAS", "SUB-1003-VAS-CRICKET", "29.50")));
            if (sep) {
                for (int w = 0; w < 4; w++) {
                    LocalDate s = LocalDate.of(2026, 8, 15).plusWeeks(w);
                    c3.add(new Charge("VAS", "Astro Daily weekly renewal", "SUB-1003-VAS-ASTRO", BigDecimal.ONE,
                            "WEEK", Money.of("49.00"), s, s.plusDays(6), null));
                }
            }
            b.addBill(1003, p, 11, SupplyType.INTRA, c3);

            // 1004: plan change PP_399 -> PP_699 on 1 Sep, prorated.
            List<Charge> c4 = sep ? List.of(
                    new Charge("PRORATION", "PP_399 16-31 Aug", "SUB-1004-PLAN", new BigDecimal("16"), "DAY",
                            Money.of("205.94"), LocalDate.of(2026, 8, 16), LocalDate.of(2026, 8, 31), null),
                    new Charge("PRORATION", "PP_699 01-15 Sep", "SUB-1004-PLAN", new BigDecimal("15"), "DAY",
                            Money.of("338.23"), LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 15), null))
                    : List.of(rental("SUB-1004-PLAN", "399.00"));
            b.addBill(1004, p, 16, SupplyType.INTER, c4);

            // 1005: the rental billed twice in September (same external reference).
            List<Charge> c5 = new ArrayList<>();
            c5.add(new Charge("RENTAL", "PP_599 monthly rental", "SUB-1005-PLAN", BigDecimal.ONE, "MONTH",
                    Money.of("599.00"), null, null, sep ? "RENT-1005-2609" : null));
            if (sep) {
                c5.add(new Charge("RENTAL", "PP_599 monthly rental", "SUB-1005-PLAN", BigDecimal.ONE, "MONTH",
                        Money.of("599.00"), null, null, "RENT-1005-2609"));
            }
            b.addBill(1005, p, 21, SupplyType.INTRA, c5);

            // 1006: a normal bill; ISD calls at Rs 6.00/min.
            Money isd = Money.of("6.00").times(BigDecimal.valueOf(ISD_MIN_1006[m]));
            b.addBill(1006, p, 1, SupplyType.INTER, List.of(rental("SUB-1006-PLAN", "499.00"),
                    new Charge("VOICE", "ISD calls", "SUB-1006-PLAN", BigDecimal.valueOf(ISD_MIN_1006[m]), "MIN", isd,
                            null, null, null)));

            for (int a = 0; a < 6; a++) {
                long account = 1001 + a;
                Money dataCharge = account == 1002 ? over2 : Money.ZERO;
                long isdMin = account == 1006 ? ISD_MIN_1006[m] : 0;
                b.addUsage(account, p, DATA_GB[a][m] * 1024L, isdMin, dataCharge,
                        account == 1006 ? isd : Money.ZERO);
            }
        }
        b.roaming.put(1001L, List.of(new BillingReadModel.RoamingUsage(SEPTEMBER, SEPTEMBER, "AE", LocalDate.of(2026, 8, 12),
                LocalDate.of(2026, 8, 18), new BigDecimal("550"), new BigDecimal("10"), 3, Money.of("1775.00"))));
        return b;
    }

    private static Charge rental(String subscriptionId, String amount) {
        return new Charge("RENTAL", "monthly rental", subscriptionId, BigDecimal.ONE, "MONTH", Money.of(amount), null,
                null, null);
    }

    // ---------------------------------------------------------------- catalogue

    public static CatalogReadModel catalog() {
        List<CatalogReadModel.Plan> plans = new ArrayList<>();
        plans.add(plan("PP_299", "299.00", 30, 0));
        plans.add(plan("PP_399", "399.00", 40, 0));
        plans.add(plan("PP_499", "499.00", 75, 0));
        plans.add(plan("PP_599", "599.00", 100, 0));
        plans.add(plan("PP_699", "699.00", 150, 0));
        plans.add(plan("PP_999", "999.00", 300, 100));
        plans.add(plan("PP_1199", "1199.00", null, 200));
        plans.add(plan("PP_1499", "1499.00", null, 500));
        List<CatalogReadModel.AddOn> addOns = List.of(
                new CatalogReadModel.AddOn("DATA_10GB", "Data add-on 10 GB", Money.of("150.00"), new BigDecimal("10240"),
                        null, null, CatalogReadModel.Validity.BILL_CYCLE, null, null),
                new CatalogReadModel.AddOn("DATA_25GB", "Data add-on 25 GB", Money.of("325.00"), new BigDecimal("25600"),
                        null, null, CatalogReadModel.Validity.BILL_CYCLE, null, null),
                new CatalogReadModel.AddOn("IR_GCC_7D", "GCC roaming pack 7 days", Money.of("899.00"),
                        new BigDecimal("1024"), new BigDecimal("100"), 20, CatalogReadModel.Validity.DAYS, 7,
                        CatalogReadModel.Band.GCC),
                new CatalogReadModel.AddOn("IR_GCC_30D", "GCC roaming pack 30 days", Money.of("2199.00"),
                        new BigDecimal("5120"), new BigDecimal("300"), 100, CatalogReadModel.Validity.DAYS, 30,
                        CatalogReadModel.Band.GCC),
                new CatalogReadModel.AddOn("IR_WORLD_10D", "World roaming pack 10 days", Money.of("2999.00"),
                        new BigDecimal("2048"), new BigDecimal("100"), 50, CatalogReadModel.Validity.DAYS, 10,
                        CatalogReadModel.Band.WORLD));
        return new CatalogReadModel() {

            @Override
            public List<Plan> plans(LocalDate onDate) {
                return plans;
            }

            @Override
            public List<AddOn> addOns(LocalDate onDate) {
                return addOns;
            }

            @Override
            public Optional<Band> roamingBand(String countryCode) {
                return "AE".equals(countryCode) ? Optional.of(Band.GCC) : Optional.empty();
            }
        };
    }

    private static CatalogReadModel.Plan plan(String code, String rental, Integer dataGb, int isdIncluded) {
        CatalogReadModel.Band dom = CatalogReadModel.Band.DOMESTIC;
        CatalogReadModel.Band gcc = CatalogReadModel.Band.GCC;
        CatalogReadModel.Band world = CatalogReadModel.Band.WORLD;
        return new CatalogReadModel.Plan(code, code, Money.of(rental), List.of(
                tariff(CatalogReadModel.UsageType.DATA_MB, dom, dataGb == null ? null : dataGb * 1024, "0.02"),
                tariff(CatalogReadModel.UsageType.VOICE_MIN, dom, null, "0.00"),
                tariff(CatalogReadModel.UsageType.SMS, dom, 3000, "1.00"),
                tariff(CatalogReadModel.UsageType.ISD_MIN, CatalogReadModel.Band.INTL, isdIncluded, "6.00"),
                tariff(CatalogReadModel.UsageType.ROAM_DATA_MB, gcc, 0, "2.00"),
                tariff(CatalogReadModel.UsageType.ROAM_VOICE_MIN, gcc, 0, "60.00"),
                tariff(CatalogReadModel.UsageType.ROAM_SMS, gcc, 0, "25.00"),
                tariff(CatalogReadModel.UsageType.ROAM_DATA_MB, world, 0, "5.00"),
                tariff(CatalogReadModel.UsageType.ROAM_VOICE_MIN, world, 0, "120.00"),
                tariff(CatalogReadModel.UsageType.ROAM_SMS, world, 0, "25.00")));
    }

    private static CatalogReadModel.TariffRate tariff(CatalogReadModel.UsageType type, CatalogReadModel.Band band,
            Integer included, String price) {
        return new CatalogReadModel.TariffRate(type, band, included == null ? null : BigDecimal.valueOf(included),
                new BigDecimal(price), "UNIT", false);
    }

    // ---------------------------------------------------------------- TMF622

    /** The subscriptions and orders of {@code inventory.json} (seed-scenarios.md §6). */
    public static ProductInventoryGateway inventory() {
        Map<Long, List<ProductInventoryGateway.ActiveProduct>> products = new HashMap<>();
        products.put(1001L, List.of(planProduct(1001, "PP_699")));
        products.put(1002L, List.of(planProduct(1002, "PP_399")));
        products.put(1003L, List.of(planProduct(1003, "PP_499"),
                vas("SUB-1003-VAS-CRICKET", "CRICKET_SCORES", "Cricket Scores", "29.50", LocalDate.of(2026, 2, 10),
                        new ProductInventoryGateway.OptInEvidence(OffsetDateTime.parse("2026-02-10T09:14:00+05:30"),
                                "SMS", OffsetDateTime.parse("2026-02-10T09:16:00+05:30"), "SMS")),
                vas("SUB-1003-VAS-ASTRO", "ASTRO_DAILY", "Astro Daily", "49.00", LocalDate.of(2026, 8, 15),
                        new ProductInventoryGateway.OptInEvidence(OffsetDateTime.parse("2026-08-15T21:03:00+05:30"),
                                "WAP", null, null))));
        products.put(1004L, List.of(planProduct(1004, "PP_699")));
        products.put(1005L, List.of(planProduct(1005, "PP_599")));
        products.put(1006L, List.of(planProduct(1006, "PP_499")));
        List<ProductInventoryGateway.ProductOrder> orders1004 = List.of(new ProductInventoryGateway.ProductOrder(
                "ORD-1004-0001", ProductInventoryGateway.OrderAction.PLAN_CHANGE, "PP_399", "PP_699",
                LocalDate.of(2026, 9, 1), "APP", "COMPLETED"));
        return new ProductInventoryGateway() {

            @Override
            public List<ActiveProduct> activeProducts(AccountId accountId) {
                return products.getOrDefault(accountId.value(), List.of());
            }

            @Override
            public List<ProductOrder> orders(AccountId accountId) {
                return accountId.value() == 1004 ? orders1004 : List.of();
            }

            @Override
            public OrderReceipt submitOrder(OrderRequest request) {
                throw new UnsupportedOperationException("read-only fixture");
            }
        };
    }

    private static ProductInventoryGateway.ActiveProduct planProduct(long account, String code) {
        return new ProductInventoryGateway.ActiveProduct("SUB-" + account + "-PLAN",
                ProductInventoryGateway.ProductType.PLAN, code, code, null, Money.ZERO, LocalDate.of(2025, 1, 1), "APP",
                Optional.empty());
    }

    private static ProductInventoryGateway.ActiveProduct vas(String id, String code, String name, String price,
            LocalDate activated, ProductInventoryGateway.OptInEvidence evidence) {
        return new ProductInventoryGateway.ActiveProduct(id, ProductInventoryGateway.ProductType.VAS, code, name,
                "Demo VAS Provider", Money.of(price), activated, "SMS", Optional.of(evidence));
    }
}
