package com.telco.billshock.tools;

import com.telco.billshock.domain.InrFormat;
import com.telco.billshock.domain.Money;

/**
 * A money amount in a tool result: the plain value and a display string that already carries
 * its GST label (llm-architecture.md §6, §10). The model copies {@code display} verbatim and
 * never computes or re-labels an amount.
 *
 * @param value 2 dp, for example {@code 2094.50}
 * @param display for example {@code ₹2,094.50 incl. GST}
 */
public record Amount(String value, String display) {

    public static Amount inclGst(Money money) {
        return money == null ? null : new Amount(money.amount().toPlainString(), InrFormat.inclGst(money));
    }

    public static Amount exclGst(Money money) {
        return money == null ? null : new Amount(money.amount().toPlainString(), InrFormat.exclGst(money));
    }

    public static Amount gst(Money money) {
        return money == null ? null : new Amount(money.amount().toPlainString(), InrFormat.gst(money));
    }

    /** A catalogue price, quoted before GST: {@code ₹599 + GST}. */
    public static Amount plusGst(Money money) {
        return money == null ? null : new Amount(money.amount().toPlainString(), InrFormat.plusGst(money));
    }
}
