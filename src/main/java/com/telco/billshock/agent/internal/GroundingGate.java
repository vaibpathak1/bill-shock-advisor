package com.telco.billshock.agent.internal;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The per-turn output gates (llm-architecture.md §7; agent.md §8.1). Every currency amount in a
 * sentence must be one of the amount strings that this turn's tool results, the pre-fetch or
 * the earlier-turn digests contain, <b>copied exactly with its GST label</b> (Q-23). An amount
 * that only the customer typed is reported separately, so it can be regenerated (A-106). Also
 * blocks the prompt canary and rewrites claims that an action was carried out.
 */
final class GroundingGate {

    enum Verdict {
        /** Send the (possibly rewritten) sentence. */
        PASS,
        /** An amount that no tool returned: stop and fall back to the template. */
        VALUE_VIOLATION,
        /** A known amount written with a different or missing label: regenerate once. */
        LABEL_MISMATCH,
        /**
         * An amount no tool returned but the customer typed (for example "why is my bill
         * ₹3,000?" or "I was promised a ₹5,000 credit"): regenerate once, then fall back
         * (gate review, 5a; A-106). The customer's figures are claims, not facts.
         */
        CUSTOMER_AMOUNT,
        /** The system prompt canary appeared: block and fall back. */
        PROMPT_LEAK
    }

    record Result(Verdict verdict, String text, boolean actionClaimRewritten) {
    }

    /** {@code ₹}, {@code Rs}, {@code Rs.} or {@code INR}, a number, and an optional GST label. */
    static final Pattern AMOUNT = Pattern.compile(
            "(?<cur>₹|(?<![\\p{L}])Rs\\.?\\s?|(?<![\\p{L}])INR\\s?)"
                    + "(?<num>\\d{1,3}(?:,\\d{2,3})+(?:\\.\\d{1,2})?|\\d+(?:\\.\\d{1,2})?)"
                    + "(?<label>\\s(?:incl\\. GST|excl\\. GST|\\+ GST|GST\\b))?");

    /** Amounts in customer text also count without a symbol when a unit follows: {@code 3000 rupees}. */
    private static final Pattern CUSTOMER_NUMBER_WITH_UNIT = Pattern.compile(
            "(?i)(?<![\\d.,])(\\d{1,3}(?:,\\d{2,3})+(?:\\.\\d{1,2})?|\\d+(?:\\.\\d{1,2})?)\\s?(?:rupees?|rs\\b\\.?|inr\\b|/-)");

    private static final Pattern FIRST_PERSON_ACTION = Pattern.compile(
            "(?i)\\b(?:i|we)(?:'ve|\\s+have|\\s+had)?\\s+(?:already\\s+|now\\s+|just\\s+)?"
                    + "(?:credited|refunded|cancell?ed|canceled|unsubscribed|changed|switched|upgraded|downgraded|"
                    + "activated|deactivated|barred|blocked|raised|filed|applied|added|removed|waived|reversed|"
                    + "processed|submitted)\\b");

    private static final Pattern PASSIVE_MONEY_OUT = Pattern.compile(
            "(?i)\\b(?:has|have)\\s+been\\s+(?:credited|refunded|waived|reversed)\\b");

    static final String ACTION_CLAIM_REPLACEMENT =
            "I haven't changed anything on your account; any change needs your confirmation first. ";

    private final String canary;
    private final Set<String> allowedForms = new HashSet<>();
    private final Set<BigDecimal> allowedValues = new HashSet<>();
    private final Set<BigDecimal> customerValues = new HashSet<>();

    GroundingGate(String canary) {
        this.canary = canary == null || canary.isBlank() ? null : canary.toLowerCase(Locale.ROOT);
    }

    /** Adds every amount string in a tool result or server context to the allowed set. */
    void allow(String toolOutput) {
        for (Token t : tokens(toolOutput)) {
            allowedForms.add(t.form());
            allowedValues.add(t.value());
        }
    }

    /** Records the amounts in a customer message, so that repeating one is recognised. */
    void customerSaid(String customerText) {
        tokens(customerText).forEach(t -> customerValues.add(t.value()));
        if (customerText != null) {
            Matcher m = CUSTOMER_NUMBER_WITH_UNIT.matcher(customerText);
            while (m.find()) {
                customerValues.add(new BigDecimal(m.group(1).replace(",", "")).stripTrailingZeros());
            }
        }
    }

    Result check(String sentence) {
        if (canary != null && sentence.toLowerCase(Locale.ROOT).contains(canary)) {
            return new Result(Verdict.PROMPT_LEAK, sentence, false);
        }
        for (Token t : tokens(sentence)) {
            if (!allowedValues.contains(t.value())) {
                return new Result(customerValues.contains(t.value()) ? Verdict.CUSTOMER_AMOUNT : Verdict.VALUE_VIOLATION,
                        sentence, false);
            }
            if (!allowedForms.contains(t.form())) {
                return new Result(Verdict.LABEL_MISMATCH, sentence, false);
            }
        }
        if (FIRST_PERSON_ACTION.matcher(sentence).find() || PASSIVE_MONEY_OUT.matcher(sentence).find()) {
            return new Result(Verdict.PASS, ACTION_CLAIM_REPLACEMENT, true);
        }
        return new Result(Verdict.PASS, sentence, false);
    }

    /** The distinct amount strings in a text, in order (for the end-of-turn digest). */
    static List<String> amountStrings(String text) {
        Set<String> forms = new LinkedHashSet<>();
        for (Token t : tokens(text)) {
            forms.add(t.form());
        }
        return List.copyOf(forms);
    }

    /**
     * @param form the amount as written, without a leading minus: symbol, number, label
     * @param value the absolute amount, for the value check
     */
    private record Token(String form, BigDecimal value) {
    }

    private static List<Token> tokens(String text) {
        if (text == null) {
            return List.of();
        }
        Matcher m = AMOUNT.matcher(text);
        List<Token> out = new ArrayList<>();
        while (m.find()) {
            BigDecimal value = new BigDecimal(m.group("num").replace(",", "")).stripTrailingZeros();
            out.add(new Token(m.group(), value));
        }
        return out;
    }
}
