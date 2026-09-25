package com.telco.billshock.actions;

import com.telco.billshock.actions.ActionRequest.AddOnPurchase;
import com.telco.billshock.actions.ActionRequest.Dispute;
import com.telco.billshock.actions.ActionRequest.Effective;
import com.telco.billshock.actions.ActionRequest.Escalation;
import com.telco.billshock.actions.ActionRequest.GoodwillCredit;
import com.telco.billshock.actions.ActionRequest.PlanChange;
import com.telco.billshock.actions.ActionRequest.ThirdPartyBarring;
import com.telco.billshock.actions.ActionRequest.VasUnsubscribe;
import com.telco.billshock.actions.GuardrailDecision.Outcome;
import com.telco.billshock.actions.GuardrailDecision.ReasonCode;
import com.telco.billshock.actions.guardrail.GuardrailChain;
import com.telco.billshock.actions.guardrail.GuardrailContext;
import com.telco.billshock.actions.guardrail.GuardrailContextFactory;
import com.telco.billshock.bss.BillingReadModel;
import com.telco.billshock.bss.ProductInventoryGateway;
import com.telco.billshock.domain.AccountId;
import com.telco.billshock.domain.BillPeriod;
import com.telco.billshock.domain.Money;
import com.telco.billshock.domain.SupplyType;
import com.telco.billshock.support.SeedScenarioFixtures;
import com.telco.billshock.support.SeedScenarioFixtures.Charge;
import com.telco.billshock.support.SeedScenarioFixtures.InMemoryBilling;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static com.telco.billshock.support.SeedScenarioFixtures.SEPTEMBER;
import static org.assertj.core.api.Assertions.assertThat;

/** deterministic-core.md §4: the guardrail chain on the seed scenarios, its boundaries, and §4.1. */
class GuardrailServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-25T06:00:00Z"), ZoneId.of("Asia/Kolkata"));

    private final InMemoryBilling billing = SeedScenarioFixtures.billing();

    private GuardrailService service(int autonomyLevel, BillingReadModel billingModel,
            ProductInventoryGateway inventory) {
        GuardrailProperties properties = new GuardrailProperties(autonomyLevel, new BigDecimal("2000.00"),
                new GuardrailProperties.Goodwill(new BigDecimal("500.00"), new BigDecimal("15"), 6), 6);
        return new GuardrailService(
                new GuardrailContextFactory(billingModel, SeedScenarioFixtures.catalog(), inventory, properties, CLOCK),
                new GuardrailChain(properties, SeedScenarioFixtures.GST));
    }

    private GuardrailDecision evaluate(long account, ActionRequest request) {
        return service(1, billing, SeedScenarioFixtures.inventory()).evaluate(AccountId.of(account), request);
    }

    private static GoodwillCredit credit(String amountExclGst, Long... lineItemIds) {
        return new GoodwillCredit(Money.of(amountExclGst), "goodwill", List.of(lineItemIds));
    }

    @Nested
    class Scenarios {

        @Test
        void vasWithoutDoubleOptInGetsAFullRefundComputedServerSide() {
            GuardrailDecision d = evaluate(1003, new VasUnsubscribe("SUB-1003-VAS-ASTRO", true));

            assertThat(d.outcome()).isEqualTo(Outcome.PROPOSE);
            assertThat(d.amountExclGst()).isEqualTo(Money.of("196.00"));
            assertThat(d.gstAmount()).isEqualTo(Money.of("35.28"));
            assertThat(d.amountInclGst()).isEqualTo(Money.of("231.28"));
            assertThat(d.lineItemIds()).containsExactly(1003260903L, 1003260904L, 1003260905L, 1003260906L);
            assertThat(d.billPeriod()).isEqualTo(SEPTEMBER);
            assertThat(d.refundAmountIncomplete()).isFalse();
            assertThat(d.autoApprove()).isFalse();
            assertThat(d.reasons()).contains(ReasonCode.CONFIRMATION_REQUIRED);
        }

        @Test
        void aVasWithDoubleOptInIsNotRefundedButMayBeUnsubscribed() {
            assertThat(evaluate(1003, new VasUnsubscribe("SUB-1003-VAS-CRICKET", true))).satisfies(d -> {
                assertThat(d.outcome()).isEqualTo(Outcome.REJECT);
                assertThat(d.reasons()).containsExactly(ReasonCode.REFUND_NOT_ELIGIBLE_OPT_IN_PRESENT);
            });
            assertThat(evaluate(1003, new VasUnsubscribe("SUB-1003-VAS-CRICKET", false))).satisfies(d -> {
                assertThat(d.outcome()).isEqualTo(Outcome.PROPOSE);
                assertThat(d.amountExclGst()).isNull();
            });
        }

        @Test
        void aRefundForASubscriptionOlderThanTheLocalHistoryIsFlaggedIncomplete() {
            ProductInventoryGateway oldAstro = inventoryWith(1003, new ProductInventoryGateway.ActiveProduct(
                    "SUB-1003-VAS-ASTRO", ProductInventoryGateway.ProductType.VAS, "ASTRO_DAILY", "Astro Daily", "P2",
                    Money.of("49.00"), LocalDate.of(2025, 12, 1), "WAP", Optional.of(
                            new ProductInventoryGateway.OptInEvidence(null, null, null, null))));

            GuardrailDecision d = service(1, billing, oldAstro).evaluate(AccountId.of(1003),
                    new VasUnsubscribe("SUB-1003-VAS-ASTRO", true));

            assertThat(d.outcome()).isEqualTo(Outcome.PROPOSE);
            assertThat(d.refundAmountIncomplete()).isTrue();
            assertThat(d.reasons()).contains(ReasonCode.REFUND_AMOUNT_INCOMPLETE);
            assertThat(d.amountInclGst()).isEqualTo(Money.of("231.28")); // local part only
        }

        @ParameterizedTest
        @CsvSource({ "1005260901", "1005260902" })
        void aDuplicateChargeIsDisputedNotCompensated(long lineItemId) {
            GuardrailDecision d = evaluate(1005, credit("100.00", lineItemId));

            assertThat(d.outcome()).isEqualTo(Outcome.REJECT);
            assertThat(d.reasons()).containsExactly(ReasonCode.USE_DISPUTE);
        }

        @Test
        void aDisputeCarriesTheCitedAmountWithGst() {
            GuardrailDecision d = evaluate(1005, new Dispute(List.of(1005260902L), "duplicate rental"));

            assertThat(d.outcome()).isEqualTo(Outcome.PROPOSE);
            assertThat(d.amountExclGst()).isEqualTo(Money.of("599.00"));
            assertThat(d.gstAmount()).isEqualTo(Money.of("107.82"));
            assertThat(d.amountInclGst()).isEqualTo(Money.of("706.82"));
        }

        @Test
        void aPlanChangeAlwaysNeedsConfirmationEvenAtLevelTwo() {
            GuardrailDecision d = service(2, billing, SeedScenarioFixtures.inventory()).evaluate(AccountId.of(1002),
                    new PlanChange("PP_499", Effective.NEXT_CYCLE));

            assertThat(d.outcome()).isEqualTo(Outcome.PROPOSE);
            assertThat(d.reasons()).containsExactly(ReasonCode.CONFIRMATION_REQUIRED);
            assertThat(d.autoApprovalEligible()).isFalse();
            assertThat(d.autoApprove()).isFalse();
        }

        @Test
        void otherActionsNeedConfirmationAndAnEscalationEscalates() {
            assertThat(evaluate(1003, new ThirdPartyBarring()).outcome()).isEqualTo(Outcome.PROPOSE);
            assertThat(evaluate(1001, new AddOnPurchase("IR_GCC_7D")).outcome()).isEqualTo(Outcome.PROPOSE);
            assertThat(evaluate(1001, new Escalation("summary", "customer asked"))).satisfies(d -> {
                assertThat(d.outcome()).isEqualTo(Outcome.ESCALATE);
                assertThat(d.reasons()).containsExactly(ReasonCode.ESCALATION_REQUESTED);
            });
        }
    }

    @Nested
    class GoodwillBoundaries {

        @Test
        void fifteenPercentOfTheBillIsInclusive() {
            // 1001: bill 2,919.32, so 15% = 437.90. Intra-state: 371.10 + 2 x 33.40 = 437.90.
            assertThat(evaluate(1001, credit("371.10"))).satisfies(d -> {
                assertThat(d.amountInclGst()).isEqualTo(Money.of("437.90"));
                assertThat(d.outcome()).isEqualTo(Outcome.PROPOSE);
                assertThat(d.supervisorRequired()).isFalse();
                assertThat(d.autoApprovalEligible()).isTrue();
                assertThat(d.autoApprove()).isFalse(); // Level 1
            });
            assertThat(evaluate(1001, credit("371.11"))).satisfies(d -> {
                assertThat(d.amountInclGst()).isEqualTo(Money.of("437.91"));
                assertThat(d.supervisorRequired()).isTrue();
                assertThat(d.reasons()).contains(ReasonCode.ABOVE_BILL_SHARE);
                assertThat(d.autoApprovalEligible()).isFalse();
            });
        }

        @Test
        void fiveHundredRupeesIsInclusive() {
            largeBill(3001, SupplyType.INTER);
            // Inter-state: 423.73 + 76.27 = 500.00; 423.74 + 76.27 = 500.01.
            assertThat(evaluate(3001, credit("423.73"))).satisfies(d -> {
                assertThat(d.amountInclGst()).isEqualTo(Money.of("500.00"));
                assertThat(d.supervisorRequired()).isFalse();
                assertThat(d.autoApprovalEligible()).isTrue();
            });
            assertThat(evaluate(3001, credit("423.74"))).satisfies(d -> {
                assertThat(d.amountInclGst()).isEqualTo(Money.of("500.01"));
                assertThat(d.supervisorRequired()).isTrue();
                assertThat(d.reasons()).contains(ReasonCode.ABOVE_AUTO_APPROVAL_LIMIT);
            });
        }

        @Test
        void twoThousandRupeesIsInclusiveAndAboveItEscalates() {
            largeBill(3002, SupplyType.INTRA);
            // Intra-state: 1,694.92 + 2 x 152.54 = 2,000.00; 1,694.93 + 2 x 152.54 = 2,000.01.
            assertThat(evaluate(3002, credit("1694.92"))).satisfies(d -> {
                assertThat(d.amountInclGst()).isEqualTo(Money.of("2000.00"));
                assertThat(d.outcome()).isEqualTo(Outcome.PROPOSE);
                assertThat(d.supervisorRequired()).isTrue();
            });
            assertThat(evaluate(3002, credit("1694.93"))).satisfies(d -> {
                assertThat(d.amountInclGst()).isEqualTo(Money.of("2000.01"));
                assertThat(d.outcome()).isEqualTo(Outcome.ESCALATE);
                assertThat(d.reasons()).containsExactly(ReasonCode.MONEY_OUT_ABOVE_ESCALATION_LIMIT);
            });
        }

        @Test
        void aPriorCreditNeedsASupervisor() {
            BillPeriod p = BillPeriod.of(2026, 9);
            billing.addBill(3003, p.minusMonths(4), 1, SupplyType.INTER,
                    List.of(Charge.of("RENTAL", "S", "20000.00"), Charge.of("CREDIT", "S", "-50.00")));
            billing.addBill(3003, p, 1, SupplyType.INTER, List.of(Charge.of("RENTAL", "S", "20000.00")));

            assertThat(evaluate(3003, credit("100.00"))).satisfies(d -> {
                assertThat(d.supervisorRequired()).isTrue();
                assertThat(d.reasons()).containsExactly(ReasonCode.PRIOR_CREDIT, ReasonCode.CONFIRMATION_REQUIRED);
            });
        }

        @Test
        void aCreditAboveTheBillOrNotPositiveIsRejected() {
            assertThat(evaluate(1006, credit("600.00")).reasons()).containsExactly(ReasonCode.EXCEEDS_BILL);
            assertThat(evaluate(1006, credit("0.00")).reasons()).containsExactly(ReasonCode.INVALID_AMOUNT);
        }

        @Test
        void levelTwoAutoApprovesOnlyWithinPolicy() {
            GuardrailService levelTwo = service(2, billing, SeedScenarioFixtures.inventory());

            assertThat(levelTwo.evaluate(AccountId.of(1001), credit("371.10")).autoApprove()).isTrue();
            assertThat(levelTwo.evaluate(AccountId.of(1001), credit("371.11")).autoApprove()).isFalse();
        }

        @Test
        void levelZeroRejectsEveryActionButEscalation() {
            GuardrailService levelZero = service(0, billing, SeedScenarioFixtures.inventory());

            assertThat(levelZero.evaluate(AccountId.of(1001), credit("10.00")).reasons())
                .containsExactly(ReasonCode.ACTIONS_DISABLED);
            assertThat(levelZero.evaluate(AccountId.of(1001), new Escalation("s", "r")).outcome())
                .isEqualTo(Outcome.ESCALATE);
        }

        private void largeBill(long account, SupplyType supply) {
            billing.addBill(account, SEPTEMBER, 1, supply, List.of(Charge.of("RENTAL", "S", "20000.00")));
        }
    }

    /**
     * Gate review item 7 (A-94 with Q-20): the goodwill tool passes an amount before GST, and
     * the chain adds GST itself, with the bill-level rule for the bill's supply type, before any
     * comparison. Each case below is under the limit before GST and over it after.
     */
    @Nested
    class ThresholdsUseTheGstInclusiveAmount {

        @Test
        void underTheCapBeforeGstButOverItAfterIsEscalated() {
            billing.addBill(3002, SEPTEMBER, 1, SupplyType.INTRA, List.of(Charge.of("RENTAL", "S", "20000.00")));

            // 1,694.93 < 2,000 before GST; + CGST 152.54 + SGST 152.54 = 2,000.01.
            GuardrailDecision d = evaluate(3002, credit("1694.93"));

            assertThat(d.amountExclGst().amount()).isLessThan(new BigDecimal("2000.00"));
            assertThat(d.gstAmount()).isEqualTo(Money.of("305.08"));
            assertThat(d.amountInclGst()).isEqualTo(Money.of("2000.01"));
            assertThat(d.outcome()).isEqualTo(Outcome.ESCALATE);
        }

        @Test
        void theBillsSupplyTypeDecidesTheGstAndSoTheOutcome() {
            billing.addBill(3002, SEPTEMBER, 1, SupplyType.INTRA, List.of(Charge.of("RENTAL", "S", "20000.00")));
            billing.addBill(3001, SEPTEMBER, 1, SupplyType.INTER, List.of(Charge.of("RENTAL", "S", "20000.00")));

            // The same 1,694.92 before GST: intra-state 2 x 152.54 = 305.08 -> 2,000.00 (at the cap, allowed);
            // inter-state IGST 305.0856 -> 305.09 -> 2,000.01 (over the cap, escalated).
            GuardrailDecision intra = evaluate(3002, credit("1694.92"));
            GuardrailDecision inter = evaluate(3001, credit("1694.92"));

            assertThat(intra.amountInclGst()).isEqualTo(Money.of("2000.00"));
            assertThat(intra.outcome()).isEqualTo(Outcome.PROPOSE);
            assertThat(inter.amountInclGst()).isEqualTo(Money.of("2000.01"));
            assertThat(inter.outcome()).isEqualTo(Outcome.ESCALATE);
        }

        @Test
        void underFiveHundredBeforeGstButOverItAfterNeedsASupervisor() {
            billing.addBill(3001, SEPTEMBER, 1, SupplyType.INTER, List.of(Charge.of("RENTAL", "S", "20000.00")));

            GuardrailDecision d = evaluate(3001, credit("423.74"));

            assertThat(d.amountInclGst()).isEqualTo(Money.of("500.01"));
            assertThat(d.supervisorRequired()).isTrue();
            assertThat(d.reasons()).contains(ReasonCode.ABOVE_AUTO_APPROVAL_LIMIT);
        }

        @Test
        void underFifteenPercentBeforeGstButOverItAfterNeedsASupervisor() {
            // 1001: 15% of 2,919.32 = 437.90. 400.00 before GST is well under it; incl. GST it is 472.00.
            GuardrailDecision d = evaluate(1001, credit("400.00"));

            assertThat(d.amountInclGst()).isEqualTo(Money.of("472.00"));
            assertThat(d.reasons()).contains(ReasonCode.ABOVE_BILL_SHARE);
            assertThat(d.autoApprovalEligible()).isFalse();
        }
    }

    /** Gate review item 4: a VAS refund is exempt from the share and prior-credit rules, not from the cap. */
    @Nested
    class VasRefundCap {

        @Test
        void aRefundOfExactlyTwoThousandInclGstIsProposed() {
            GuardrailDecision d = refund(3004, "1694.92");

            assertThat(d.amountInclGst()).isEqualTo(Money.of("2000.00"));
            assertThat(d.outcome()).isEqualTo(Outcome.PROPOSE);
            assertThat(d.reasons()).containsExactly(ReasonCode.CONFIRMATION_REQUIRED);
        }

        @Test
        void aRefundAboveTwoThousandInclGstIsEscalated() {
            GuardrailDecision d = refund(3004, "1694.93");

            assertThat(d.amountExclGst()).isEqualTo(Money.of("1694.93"));
            assertThat(d.amountInclGst()).isEqualTo(Money.of("2000.01"));
            assertThat(d.outcome()).isEqualTo(Outcome.ESCALATE);
            assertThat(d.reasons()).containsExactly(ReasonCode.MONEY_OUT_ABOVE_ESCALATION_LIMIT);
        }

        @Test
        void aRefundIsExemptFromTheBillShareFiveHundredAndPriorCreditRules() {
            // 450.00 + 81.00 GST = 531.00: above ₹500 and far above 15% of this bill, with a prior credit.
            billing.addBill(3005, SEPTEMBER.minusMonths(2), 1, SupplyType.INTRA,
                    List.of(Charge.of("RENTAL", "S", "100.00"), Charge.of("CREDIT", "S", "-10.00")));
            GuardrailDecision d = refund(3005, "450.00");

            assertThat(d.amountInclGst()).isEqualTo(Money.of("531.00"));
            assertThat(d.outcome()).isEqualTo(Outcome.PROPOSE);
            assertThat(d.supervisorRequired()).isFalse();
            assertThat(d.reasons()).containsExactly(ReasonCode.CONFIRMATION_REQUIRED);
        }

        private GuardrailDecision refund(long account, String vasCharges) {
            String subscription = "SUB-" + account + "-VAS";
            billing.addBill(account, SEPTEMBER, 1, SupplyType.INTRA,
                    List.of(Charge.of("RENTAL", "S", "100.00"), Charge.of("VAS", subscription, vasCharges)));
            ProductInventoryGateway inventory = inventoryWith(account, new ProductInventoryGateway.ActiveProduct(
                    subscription, ProductInventoryGateway.ProductType.VAS, "VAS_X", "VAS X", "P", Money.of("10.00"),
                    LocalDate.of(2026, 8, 15), "WAP", Optional.of(new ProductInventoryGateway.OptInEvidence(null,
                            null, null, null))));
            return service(1, billing, inventory).evaluate(AccountId.of(account), new VasUnsubscribe(subscription, true));
        }
    }

    @Nested
    class References {

        @Test
        void unknownProductsAreRejected() {
            assertThat(evaluate(1002, new PlanChange("PP_X", Effective.IMMEDIATE)).reasons())
                .containsExactly(ReasonCode.UNKNOWN_PLAN);
            assertThat(evaluate(1002, new PlanChange("PP_399", Effective.IMMEDIATE)).reasons())
                .containsExactly(ReasonCode.ALREADY_ON_PLAN);
            assertThat(evaluate(1002, new AddOnPurchase("NOPE")).reasons()).containsExactly(ReasonCode.UNKNOWN_ADD_ON);
            assertThat(evaluate(1001, new VasUnsubscribe("SUB-1001-PLAN", true)).reasons())
                .containsExactly(ReasonCode.NOT_A_VAS);
        }

        @Test
        void citedLinesMustBeChargesOnOneBill() {
            assertThat(evaluate(1005, new Dispute(List.of(1005260903L), "tax")).reasons())
                .containsExactly(ReasonCode.TAX_LINE_CITED);
            assertThat(evaluate(1005, new Dispute(List.of(1005260801L, 1005260901L), "two bills")).reasons())
                .containsExactly(ReasonCode.LINE_ITEMS_ON_SEVERAL_BILLS);
            assertThat(evaluate(1005, new Dispute(List.of(), "none")).reasons())
                .containsExactly(ReasonCode.NO_LINE_ITEMS);
        }
    }

    /**
     * Addition 1 (owner, 2026-09-25): the guardrail facts are built server-side from the read
     * models, and a request cannot pass facts that bypass that lookup (deterministic-core.md §4.1).
     */
    @Nested
    class FactsComeFromTheServer {

        /** Everything an action tool may legitimately choose. Nothing else may appear on a request. */
        private static final Set<String> CHOICES = Set.of("amountExclGst", "reason", "lineItemIds", "subscriptionId",
                "requestRefund", "planCode", "effective", "addOnCode", "summary");

        @Test
        void requestsCarryOnlyChoicesNeverFacts() {
            List<RecordComponent> components = Arrays.stream(ActionRequest.class.getPermittedSubclasses())
                .flatMap(type -> Arrays.stream(type.getRecordComponents()))
                .toList();

            assertThat(ActionRequest.class.getPermittedSubclasses()).hasSize(7);
            assertThat(components).extracting(RecordComponent::getName).allMatch(CHOICES::contains);
            assertThat(components).extracting(RecordComponent::getType)
                .allMatch(type -> type == Money.class || type == String.class || type == List.class
                        || type == boolean.class || type == Effective.class);
        }

        @Test
        void noPublicEntryPointAcceptsAContextOrAnythingButAnAccountAndARequest() throws Exception {
            List<Method> entryPoints = Arrays.stream(GuardrailService.class.getDeclaredMethods())
                .filter(m -> Modifier.isPublic(m.getModifiers()))
                .toList();

            assertThat(entryPoints).singleElement().satisfies(m -> {
                assertThat(m.getName()).isEqualTo("evaluate");
                assertThat(m.getParameterTypes()).containsExactly(AccountId.class, ActionRequest.class);
            });
            assertThat(Arrays.stream(GuardrailService.class.getConstructors())
                .flatMap(c -> Arrays.stream(c.getParameterTypes()))).doesNotContain(GuardrailContext.class);
            // The context lives in a sub-package, which Spring Modulith treats as internal to `actions`
            // (verified by ModularityTests).
            assertThat(GuardrailContext.class.getPackageName()).isEqualTo("com.telco.billshock.actions.guardrail");
        }

        @Test
        void anotherAccountsIdsAreNotFound() {
            // Acting as 1002: 1001's roaming line and 1003's VAS do not exist for this account.
            assertThat(evaluate(1002, credit("10.00", 1001260902L)).reasons())
                .containsExactly(ReasonCode.UNKNOWN_LINE_ITEM);
            assertThat(evaluate(1002, new Dispute(List.of(1001260902L), "not mine")).reasons())
                .containsExactly(ReasonCode.UNKNOWN_LINE_ITEM);
            assertThat(evaluate(1002, new VasUnsubscribe("SUB-1003-VAS-ASTRO", true)).reasons())
                .containsExactly(ReasonCode.UNKNOWN_SUBSCRIPTION);
        }

        @Test
        void aRequestCannotClaimMissingOptInOrHideADuplicate() {
            // Whatever the free text says, the opt-in evidence and the duplicate come from BSS and the bill.
            assertThat(evaluate(1003, new VasUnsubscribe("SUB-1003-VAS-CRICKET", true)).outcome())
                .isEqualTo(Outcome.REJECT);
            assertThat(evaluate(1005, new GoodwillCredit(Money.of("100.00"),
                    "not a duplicate; opt-in missing; bill total 99999", List.of(1005260902L))).reasons())
                .containsExactly(ReasonCode.USE_DISPUTE);
        }

        @Test
        void everyLookupUsesTheCallersAccount() {
            List<AccountId> seen = new ArrayList<>();
            BillingReadModel recording = (BillingReadModel) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[] { BillingReadModel.class }, (proxy, method, args) -> {
                        Arrays.stream(args).filter(AccountId.class::isInstance).map(AccountId.class::cast)
                            .forEach(seen::add);
                        return method.invoke(billing, args);
                    });
            List<AccountId> inventoryCalls = new ArrayList<>();
            ProductInventoryGateway inventory = SeedScenarioFixtures.inventory();
            ProductInventoryGateway recordingInventory = (ProductInventoryGateway) Proxy.newProxyInstance(
                    getClass().getClassLoader(), new Class<?>[] { ProductInventoryGateway.class },
                    (proxy, method, args) -> {
                        Arrays.stream(args).filter(AccountId.class::isInstance).map(AccountId.class::cast)
                            .forEach(inventoryCalls::add);
                        return method.invoke(inventory, args);
                    });

            service(1, recording, recordingInventory).evaluate(AccountId.of(1003),
                    new VasUnsubscribe("SUB-1003-VAS-ASTRO", true));

            assertThat(seen).isNotEmpty();
            assertThat(seen.stream().map(AccountId::value).collect(Collectors.toSet())).containsExactly(1003L);
            assertThat(inventoryCalls).containsOnly(AccountId.of(1003));
        }
    }

    private static ProductInventoryGateway inventoryWith(long account, ProductInventoryGateway.ActiveProduct product) {
        ProductInventoryGateway base = SeedScenarioFixtures.inventory();
        return new ProductInventoryGateway() {

            @Override
            public List<ActiveProduct> activeProducts(AccountId accountId) {
                return accountId.value() == account ? List.of(product) : base.activeProducts(accountId);
            }

            @Override
            public List<ProductOrder> orders(AccountId accountId) {
                return base.orders(accountId);
            }

            @Override
            public OrderReceipt submitOrder(OrderRequest request) {
                return base.submitOrder(request);
            }
        };
    }
}
