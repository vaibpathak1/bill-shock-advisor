package com.telco.billshock.actions.internal;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.telco.billshock.actions.ActionEffect;
import com.telco.billshock.actions.ActionType;
import com.telco.billshock.bss.CustomerBillGateway;
import com.telco.billshock.bss.CustomerBillGateway.AdjustmentRequest;
import com.telco.billshock.bss.CustomerBillGateway.AdjustmentType;
import com.telco.billshock.bss.ProductInventoryGateway;
import com.telco.billshock.bss.ProductInventoryGateway.OrderAction;
import com.telco.billshock.bss.ProductInventoryGateway.OrderRequest;

/**
 * VAS unsubscribe, then the refund if one was proposed (actions.md §6.2). The unsubscribe goes
 * first: stopping future charges matters most. A refund rejected after a successful unsubscribe
 * leaves the action {@code FAILED} with the unsubscribe step recorded as done (§12).
 */
@Component
class VasUnsubscribeExecutor implements ActionExecutor {

    private final ProductInventoryGateway inventory;
    private final CustomerBillGateway bills;

    VasUnsubscribeExecutor(ProductInventoryGateway inventory, CustomerBillGateway bills) {
        this.inventory = inventory;
        this.bills = bills;
    }

    @Override
    public boolean supports(ActionType type) {
        return type == ActionType.VAS_UNSUBSCRIBE;
    }

    @Override
    public List<Step> steps(ActionRow a) {
        List<Step> steps = new ArrayList<>();
        steps.add(new Step(ActionEffect.UNSUBSCRIBE, () -> inventory.submitOrder(new OrderRequest(a.account(),
                OrderAction.VAS_UNSUBSCRIBE, a.params().subscriptionId(), null, null, a.bssKey()))
            .orderId()));
        if (Boolean.TRUE.equals(a.params().requestRefund()) && a.amount() != null) {
            steps.add(new Step(ActionEffect.REFUND, () -> bills.requestAdjustment(new AdjustmentRequest(a.account(),
                    BssCalls.period(a.params().billPeriod()), AdjustmentType.REFUND, a.amount(), a.gstAmount(),
                    "Refund of VAS charges without a double opt-in record", a.bssKey()))
                .adjustmentId()));
        }
        return steps;
    }
}
