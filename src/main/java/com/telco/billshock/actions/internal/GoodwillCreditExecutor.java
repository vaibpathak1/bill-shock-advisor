package com.telco.billshock.actions.internal;

import java.util.List;

import org.springframework.stereotype.Component;

import com.telco.billshock.actions.ActionEffect;
import com.telco.billshock.actions.ActionType;
import com.telco.billshock.bss.CustomerBillGateway;
import com.telco.billshock.bss.CustomerBillGateway.AdjustmentRequest;
import com.telco.billshock.bss.CustomerBillGateway.AdjustmentType;

/** A goodwill credit: one TMF678 adjustment, GST included (Q-20). */
@Component
class GoodwillCreditExecutor implements ActionExecutor {

    private final CustomerBillGateway bills;

    GoodwillCreditExecutor(CustomerBillGateway bills) {
        this.bills = bills;
    }

    @Override
    public boolean supports(ActionType type) {
        return type == ActionType.GOODWILL_CREDIT;
    }

    @Override
    public List<Step> steps(ActionRow a) {
        return List.of(new Step(ActionEffect.CREDIT, () -> bills.requestAdjustment(new AdjustmentRequest(a.account(),
                BssCalls.period(a.params().billPeriod()), AdjustmentType.GOODWILL_CREDIT, a.amount(), a.gstAmount(),
                "Goodwill credit: " + a.params().reason(), a.bssKey()))
            .adjustmentId()));
    }
}
