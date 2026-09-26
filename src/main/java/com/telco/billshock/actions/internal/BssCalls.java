package com.telco.billshock.actions.internal;

import java.time.YearMonth;

import com.telco.billshock.domain.BillPeriod;

final class BssCalls {

    private BssCalls() {
    }

    static BillPeriod period(String yyyyMm) {
        return yyyyMm == null ? null : BillPeriod.of(YearMonth.parse(yyyyMm));
    }
}
