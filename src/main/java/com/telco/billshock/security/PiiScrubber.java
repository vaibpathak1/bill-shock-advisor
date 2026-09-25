package com.telco.billshock.security;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Removes personal identifiers from customer free text before it is stored or sent to the LLM
 * (security.md §6.2; 5a answer Q-F). The scrubbed text is what {@code chat_messages} holds.
 * Amounts ({@code ₹2,094.50}), periods and bill or line-item ids are left alone (A-105).
 *
 * <p>Order matters: e-mail, then card (13–19 digits, Luhn), then phone (so {@code +91} plus 10
 * digits is not read as a 12-digit id), then Aadhaar-like (12 digits), then bank details.
 */
public final class PiiScrubber {

    /** Not inside a longer number or an amount such as 2,094.50; sentence punctuation is fine. */
    private static final String START = "(?<![\\d])(?<!\\d[.,])";
    private static final String END = "(?!\\d)(?![.,]\\d)";

    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");

    /** 13–19 digits, optionally in groups separated by single spaces or dashes. */
    private static final Pattern CARD_CANDIDATE = Pattern.compile(START + "\\d(?:[ -]?\\d){12,18}" + END);

    private static final Pattern AADHAAR = Pattern.compile(START + "\\d{4}[ -]?\\d{4}[ -]?\\d{4}" + END);

    /** Any +91 / 0091 number, or a bare 10-digit number in India's mobile series 6–9. */
    private static final Pattern PHONE = Pattern.compile(
            "(?:(?:\\+|00)91[ -]?\\d{5}[ -]?\\d{5}|" + START + "(?<!\\+)(?:91[ -]?|0)?[6-9]\\d{4}[ -]?\\d{5})" + END);

    private static final Pattern IFSC = Pattern.compile("\\b[A-Z]{4}0[A-Z0-9]{6}\\b");

    private static final Pattern BANK_ACCOUNT = Pattern.compile(
            "(?i)\\b(account|acct|a/c)(\\s*(no\\.?|number|#))?\\s*[:\\-]?\\s*\\d{9,18}\\b");

    private PiiScrubber() {
    }

    public static String scrub(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String result = EMAIL.matcher(text).replaceAll("[EMAIL]");
        result = replaceCards(result);
        result = PHONE.matcher(result).replaceAll("[PHONE]");
        result = AADHAAR.matcher(result).replaceAll("[ID]");
        result = IFSC.matcher(result).replaceAll("[BANK]");
        return BANK_ACCOUNT.matcher(result).replaceAll("$1 [BANK]");
    }

    private static String replaceCards(String text) {
        Matcher m = CARD_CANDIDATE.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String digits = m.group().replaceAll("\\D", "");
            m.appendReplacement(out, luhnValid(digits) ? "[CARD]" : Matcher.quoteReplacement(m.group()));
        }
        m.appendTail(out);
        return out.toString();
    }

    static boolean luhnValid(String digits) {
        int sum = 0;
        boolean dbl = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int d = digits.charAt(i) - '0';
            if (dbl) {
                d *= 2;
                if (d > 9) {
                    d -= 9;
                }
            }
            sum += d;
            dbl = !dbl;
        }
        return sum % 10 == 0;
    }
}
