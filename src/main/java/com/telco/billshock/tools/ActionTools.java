package com.telco.billshock.tools;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import com.telco.billshock.actions.ActionRequest;
import com.telco.billshock.actions.ActionStatus;
import com.telco.billshock.actions.ActionView;
import com.telco.billshock.actions.GuardrailDecision.ReasonCode;
import com.telco.billshock.actions.ProposalResult;
import com.telco.billshock.actions.ProposedActionService;
import com.telco.billshock.domain.AccountId;
import com.telco.billshock.domain.Money;
import com.telco.billshock.security.CurrentCustomer;

/**
 * Resolution tools (SPEC §4.4; actions.md §3). Each one only <b>proposes</b>: it creates a
 * {@code ProposedAction} through the guardrails, and nothing changes until the customer confirms
 * (SPEC §4.3 rule 3). The account comes from the SecurityContext, the conversation from the tool
 * context set by the orchestrator; no parameter can name either (rule 2).
 */
@Component
public class ActionTools {

    /** Tool-context key of the conversation id (a {@link UUID}), set by the orchestrator. */
    public static final String CONVERSATION_ID = "billshock.conversationId";

    private final ProposedActionService actions;

    public ActionTools(ProposedActionService actions) {
        this.actions = actions;
    }

    @Tool(name = "proposeGoodwillCredit", description = """
            Proposes a goodwill credit on the customer's bill; nothing changes until the customer confirms. \
            amountExclGst must be an amount before GST copied exactly from a tool result, for example a simulated \
            option's savingExclGst for charges a pack would have covered. lineItemIds are the charges it \
            compensates, all from one bill. Not for a billing error (use raiseDispute) or a VAS without double \
            opt-in (use proposeVasUnsubscribe with a refund).""")
    public ToolResult proposeGoodwillCredit(
            @ToolParam(description = "The credit before GST, copied exactly from a tool result") String amountExclGst,
            @ToolParam(description = "One plain sentence: why the credit is fair") String reason,
            @ToolParam(description = "Line item ids of the charges the credit compensates, from one bill") List<Long> lineItemIds,
            ToolContext toolContext) {
        Money amount;
        try {
            amount = parseAmount(amountExclGst);
        }
        catch (IllegalArgumentException e) {
            return ToolMessage.invalid("amountExclGst must be an amount such as 123.45, copied from a tool result");
        }
        return propose(toolContext, new ActionRequest.GoodwillCredit(amount, reason, ids(lineItemIds)));
    }

    @Tool(name = "proposeVasUnsubscribe", description = """
            Proposes unsubscribing the customer from a value-added service (VAS), by its subscriptionId from \
            getActiveSubscriptions. Set requestRefund to true only when the subscription has no complete double \
            opt-in record; the server computes the refund. Nothing changes until the customer confirms.""")
    public ToolResult proposeVasUnsubscribe(
            @ToolParam(description = "The subscriptionId from getActiveSubscriptions") String subscriptionId,
            @ToolParam(description = "Also refund the subscription's charges (only without a double opt-in)") boolean requestRefund,
            ToolContext toolContext) {
        if (subscriptionId == null || subscriptionId.isBlank()) {
            return ToolMessage.invalid("subscriptionId is required");
        }
        return propose(toolContext, new ActionRequest.VasUnsubscribe(subscriptionId.strip(), requestRefund));
    }

    @Tool(name = "proposeThirdPartyBarring", description = """
            Proposes blocking third-party value-added service (VAS) charges on the customer's number, so no new \
            third-party subscription can bill them. Offer it after an unwanted VAS. Nothing changes until the \
            customer confirms.""")
    public ToolResult proposeThirdPartyBarring(ToolContext toolContext) {
        return propose(toolContext, new ActionRequest.ThirdPartyBarring());
    }

    @Tool(name = "proposePlanChange", description = """
            Proposes moving the customer to another plan, by its code from simulatePlans or searchPlanCatalog. \
            effective is NEXT_CYCLE (from the next bill cycle, no proration; the usual choice) or IMMEDIATE. \
            A plan change always needs the customer's confirmation.""")
    public ToolResult proposePlanChange(
            @ToolParam(description = "The plan code, for example from simulatePlans") String planCode,
            @ToolParam(description = "NEXT_CYCLE or IMMEDIATE") String effective,
            ToolContext toolContext) {
        ActionRequest.Effective when;
        try {
            when = ActionRequest.Effective.valueOf(effective == null ? "NEXT_CYCLE" : effective.strip().toUpperCase());
        }
        catch (IllegalArgumentException e) {
            return ToolMessage.invalid("effective must be NEXT_CYCLE or IMMEDIATE");
        }
        if (planCode == null || planCode.isBlank()) {
            return ToolMessage.invalid("planCode is required");
        }
        return propose(toolContext, new ActionRequest.PlanChange(planCode.strip(), when));
    }

    @Tool(name = "proposeAddOn", description = """
            Proposes adding an add-on pack to the customer's plan, by its code from searchPlanCatalog or \
            simulatePlans. Nothing changes until the customer confirms.""")
    public ToolResult proposeAddOn(
            @ToolParam(description = "The add-on code") String addOnCode, ToolContext toolContext) {
        if (addOnCode == null || addOnCode.isBlank()) {
            return ToolMessage.invalid("addOnCode is required");
        }
        return propose(toolContext, new ActionRequest.AddOnPurchase(addOnCode.strip()));
    }

    @Tool(name = "raiseDispute", description = """
            Proposes a billing dispute for charges that look wrong, such as a duplicate line item: cite the line \
            item ids of the wrong charges (for a duplicate, the extra copy), all from one bill. The server \
            computes the disputed amount. Nothing is filed until the customer confirms.""")
    public ToolResult raiseDispute(
            @ToolParam(description = "Line item ids of the wrong charges, from one bill") List<Long> lineItemIds,
            @ToolParam(description = "One plain sentence: what is wrong") String reason,
            ToolContext toolContext) {
        return propose(toolContext, new ActionRequest.Dispute(ids(lineItemIds), reason));
    }

    ToolResult propose(ToolContext toolContext, ActionRequest request) {
        AccountId account = CurrentCustomer.require();
        UUID conversationId = conversationId(toolContext);
        return result(actions.propose(account, conversationId, request));
    }

    static UUID conversationId(ToolContext toolContext) {
        Object id = toolContext == null ? null : toolContext.getContext().get(CONVERSATION_ID);
        if (!(id instanceof UUID uuid)) {
            throw new IllegalStateException("The orchestrator must put the conversation id into the tool context");
        }
        return uuid;
    }

    static ToolResult result(ProposalResult result) {
        if (result.kind() == ProposalResult.Kind.NOT_POSSIBLE) {
            return ActionToolResult.notPossible(notPossible(result.reasons()));
        }
        ActionView a = result.action().orElseThrow();
        String message = switch (result.kind()) {
            case PROPOSED -> "Proposed. Nothing has changed yet. The customer confirms or rejects it with the buttons"
                    + " shown with this proposal." + (a.needsSupervisorReview()
                            ? " After the customer confirms, a supervisor reviews it before it is applied." : "");
            case ALREADY_PROPOSED -> a.status() == ActionStatus.EXECUTED ? "This was already done earlier. Do not"
                    + " propose it again." : "This is already proposed: " + a.message() + " Do not propose it again.";
            default -> a.message();
        };
        return new ActionToolResult(result.kind().name(), a.actionId(), a.type().name(), a.status().name(), a.summary(),
                a.amount() == null ? null : new Amount(a.amount().value(), a.amount().display()),
                a.status() == ActionStatus.PENDING_CONFIRMATION, a.needsSupervisorReview() ? Boolean.TRUE : null,
                a.reference(), message);
    }

    /** Neutral wording per reason group: no codes, no thresholds (SPEC §4.5). */
    static String notPossible(List<ReasonCode> reasons) {
        for (ReasonCode r : reasons) {
            switch (r) {
                case USE_DISPUTE -> {
                    return "These charges look like a billing error. Use raiseDispute for them instead of a goodwill"
                            + " credit.";
                }
                case REFUND_NOT_ELIGIBLE_OPT_IN_PRESENT -> {
                    return "This subscription has a complete double opt-in record, so no refund can be proposed."
                            + " Unsubscribing without a refund is possible.";
                }
                case NOT_A_VAS -> {
                    return "This subscription is not a value-added service.";
                }
                case NO_REFUNDABLE_CHARGES -> {
                    return "There are no charges of this subscription to refund.";
                }
                case ALREADY_ON_PLAN -> {
                    return "The customer is already on this plan.";
                }
                case INVALID_AMOUNT, EXCEEDS_BILL -> {
                    return "This amount cannot be proposed.";
                }
                case ACTIONS_DISABLED -> {
                    return "Changes cannot be proposed right now. Explain the options instead.";
                }
                case UNKNOWN_LINE_ITEM, UNKNOWN_SUBSCRIPTION, UNKNOWN_PLAN, UNKNOWN_ADD_ON, NO_BILL,
                        LINE_ITEMS_ON_SEVERAL_BILLS, TAX_LINE_CITED, NO_LINE_ITEMS -> {
                    return "An id in the request was not found on this account or cannot be used here. Look the ids"
                            + " up with a tool first: line items from one bill, and no TAX lines.";
                }
                default -> {
                }
            }
        }
        return "This cannot be proposed.";
    }

    /** Accepts {@code 876.00}, {@code 876} or a copied display string such as {@code ₹876.00 excl. GST}. */
    public static Money parseAmount(String text) {
        if (text == null) {
            throw new IllegalArgumentException("no amount");
        }
        String number = text.replace("₹", "").replace("excl. GST", "").replace(",", "").strip();
        BigDecimal value = new BigDecimal(number);
        if (value.scale() < 2) {
            value = value.setScale(2);
        }
        return Money.of(value);
    }

    private static List<Long> ids(List<Long> lineItemIds) {
        return lineItemIds == null ? List.of() : lineItemIds.stream().filter(id -> id != null).toList();
    }
}
