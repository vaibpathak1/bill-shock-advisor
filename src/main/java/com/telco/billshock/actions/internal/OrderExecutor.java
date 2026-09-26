package com.telco.billshock.actions.internal;

import java.util.List;

import org.springframework.stereotype.Component;

import com.telco.billshock.actions.ActionEffect;
import com.telco.billshock.actions.ActionType;
import com.telco.billshock.bss.ProductInventoryGateway;
import com.telco.billshock.bss.ProductInventoryGateway.OrderAction;
import com.telco.billshock.bss.ProductInventoryGateway.OrderRequest;

/** Third-party barring, plan change and add-on: one TMF622 order each. */
@Component
class OrderExecutor implements ActionExecutor {

    private final ProductInventoryGateway inventory;

    OrderExecutor(ProductInventoryGateway inventory) {
        this.inventory = inventory;
    }

    @Override
    public boolean supports(ActionType type) {
        return type == ActionType.THIRD_PARTY_BARRING || type == ActionType.PLAN_CHANGE || type == ActionType.ADD_ON;
    }

    @Override
    public List<Step> steps(ActionRow a) {
        OrderRequest order = switch (a.type()) {
            case THIRD_PARTY_BARRING ->
                new OrderRequest(a.account(), OrderAction.THIRD_PARTY_BARRING, null, null, null, a.bssKey());
            case PLAN_CHANGE -> new OrderRequest(a.account(), OrderAction.PLAN_CHANGE, null, a.params().planCode(),
                    a.params().effective(), a.bssKey());
            case ADD_ON ->
                new OrderRequest(a.account(), OrderAction.ADD_ON, null, a.params().addOnCode(), null, a.bssKey());
            default -> throw new IllegalArgumentException("Not an order: " + a.type());
        };
        ActionEffect effect = switch (a.type()) {
            case THIRD_PARTY_BARRING -> ActionEffect.BARRING;
            case PLAN_CHANGE -> ActionEffect.PLAN_CHANGE;
            default -> ActionEffect.ADD_ON;
        };
        return List.of(new Step(effect, () -> inventory.submitOrder(order).orderId()));
    }
}
