package com.telco.billshock.actions;

import com.telco.billshock.domain.Money;

import java.util.List;
import java.util.Objects;

/**
 * What an action tool asks for (SPEC §4.4). These records carry **only** what the LLM may
 * legitimately choose: which line items, subscription, plan or add-on, a proposed goodwill
 * amount and free text. They deliberately have no field for the account, a bill total, a
 * supply type, opt-in evidence, credit history, a duplicate flag or a refund amount: those
 * facts are looked up server-side by {@link GuardrailService} (deterministic-core.md §4.1).
 */
public sealed interface ActionRequest {

    /**
     * @param amountExclGst the proposed credit before GST, copied from a tool result; GST is
     *        added server-side (Q-20)
     * @param reason free text; untrusted
     */
    record GoodwillCredit(Money amountExclGst, String reason, List<Long> lineItemIds) implements ActionRequest {

        public GoodwillCredit {
            Objects.requireNonNull(amountExclGst, "amountExclGst");
            lineItemIds = List.copyOf(lineItemIds);
        }
    }

    /** @param requestRefund also refund the subscription's charges (only without double opt-in) */
    record VasUnsubscribe(String subscriptionId, boolean requestRefund) implements ActionRequest {

        public VasUnsubscribe {
            Objects.requireNonNull(subscriptionId, "subscriptionId");
        }
    }

    record ThirdPartyBarring() implements ActionRequest {
    }

    record PlanChange(String planCode, Effective effective) implements ActionRequest {

        public PlanChange {
            Objects.requireNonNull(planCode, "planCode");
            Objects.requireNonNull(effective, "effective");
        }
    }

    enum Effective {
        IMMEDIATE, NEXT_CYCLE
    }

    record AddOnPurchase(String addOnCode) implements ActionRequest {

        public AddOnPurchase {
            Objects.requireNonNull(addOnCode, "addOnCode");
        }
    }

    /** @param reason free text; untrusted */
    record Dispute(List<Long> lineItemIds, String reason) implements ActionRequest {

        public Dispute {
            lineItemIds = List.copyOf(lineItemIds);
        }
    }

    /** @param summary masked text only (security.md) */
    record Escalation(String summary, String reason) implements ActionRequest {
    }
}
