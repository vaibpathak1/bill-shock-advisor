package com.telco.billshock.domain;

import java.math.BigDecimal;

/**
 * Formats rupee amounts for tool results, fallback templates and notifications, with
 * Indian digit grouping (en-IN): the last three digits, then groups of two, for example
 * {@code ₹1,23,456.78}. Every customer-facing amount says whether it includes GST (Q-23);
 * the model copies these strings verbatim and the grounding check matches them
 * (llm-architecture.md §7, §10).
 *
 * <p>{@link java.text.DecimalFormat} supports only one grouping size, so it cannot produce
 * lakh grouping; the grouping is done here.
 */
public final class InrFormat {

    private static final String RUPEE = "₹";

    private InrFormat() {
    }

    /** {@code ₹1,23,456.78}; a negative amount is {@code -₹12.00}. */
    public static String amount(Money money) {
        BigDecimal value = money.amount();
        String sign = value.signum() < 0 ? "-" : "";
        String plain = value.abs().toPlainString();
        int dot = plain.indexOf('.');
        return sign + RUPEE + group(plain.substring(0, dot)) + plain.substring(dot);
    }

    /** {@code ₹2,832.00 incl. GST}. */
    public static String inclGst(Money money) {
        return amount(money) + " incl. GST";
    }

    /** {@code ₹2,400.00 excl. GST}. */
    public static String exclGst(Money money) {
        return amount(money) + " excl. GST";
    }

    /**
     * A catalogue price before GST, as in SPEC §4.6: {@code ₹599 + GST}, or {@code ₹29.50 + GST}
     * when the price has paise.
     */
    public static String plusGst(Money money) {
        String formatted = amount(money);
        if (formatted.endsWith(".00")) {
            formatted = formatted.substring(0, formatted.length() - 3);
        }
        return formatted + " + GST";
    }

    private static String group(String digits) {
        if (digits.length() <= 3) {
            return digits;
        }
        String lastThree = digits.substring(digits.length() - 3);
        String rest = digits.substring(0, digits.length() - 3);
        StringBuilder grouped = new StringBuilder();
        int firstGroup = rest.length() % 2 == 0 ? 2 : 1;
        grouped.append(rest, 0, firstGroup);
        for (int i = firstGroup; i < rest.length(); i += 2) {
            grouped.append(',').append(rest, i, i + 2);
        }
        return grouped.append(',').append(lastThree).toString();
    }
}
