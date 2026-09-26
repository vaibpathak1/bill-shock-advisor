package com.telco.billshock.agent.internal;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
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

    /** Verbs that say an account change was made; each maps to the effect it claims (or none: generic). */
    private static final String CLAIM_VERBS = "credited|refunded|cancell?ed|canceled|unsubscribed|changed|switched|"
            + "upgraded|downgraded|activated|deactivated|barred|blocked|raised|filed|applied|added|removed|waived|"
            + "reversed|processed|submitted|issued";

    private static final Pattern FIRST_PERSON_ACTION = Pattern.compile(
            "(?i)\\b(?:i|we)(?:'ve|\\s+have|\\s+had)?\\s+(?:already\\s+|now\\s+|just\\s+)?(?<verb>" + CLAIM_VERBS
                    + ")\\b");

    /**
     * Passive claims: money out, unsubscribe and barring, and generic verbs ("your refund has been
     * processed"). Not "changed", "activated", "applied" or "issued": bill explanations use them ("your
     * bill has been issued", the customer's own past plan change in scenario 4) and must pass.
     */
    private static final Pattern PASSIVE_ACTION = Pattern.compile(
            "(?i)\\b(?:has|have)\\s+been\\s+(?:already\\s+|now\\s+)?(?<verb>credited|refunded|waived|reversed|"
                    + "cancell?ed|canceled|unsubscribed|deactivated|barred|blocked|processed|raised|filed|"
                    + "submitted)\\b");

    private static final Map<String, String> VERB_EFFECT = Map.ofEntries(Map.entry("credited", "CREDIT"),
            Map.entry("waived", "CREDIT"), Map.entry("reversed", "CREDIT"), Map.entry("refunded", "REFUND"),
            Map.entry("cancelled", "UNSUBSCRIBE"), Map.entry("canceled", "UNSUBSCRIBE"),
            Map.entry("unsubscribed", "UNSUBSCRIBE"), Map.entry("deactivated", "UNSUBSCRIBE"),
            Map.entry("removed", "UNSUBSCRIBE"), Map.entry("barred", "BARRING"), Map.entry("blocked", "BARRING"),
            Map.entry("changed", "PLAN_CHANGE"), Map.entry("switched", "PLAN_CHANGE"),
            Map.entry("upgraded", "PLAN_CHANGE"), Map.entry("downgraded", "PLAN_CHANGE"),
            Map.entry("activated", "ADD_ON"), Map.entry("added", "ADD_ON"));

    /** For generic verbs (processed, applied, raised, ...): the effect named by the sentence's noun. */
    private static final Map<Pattern, String> NOUN_EFFECT = Map.of(
            Pattern.compile("(?i)\\brefund"), "REFUND",
            Pattern.compile("(?i)\\bcredit"), "CREDIT",
            Pattern.compile("(?i)\\bdispute"), "DISPUTE",
            Pattern.compile("(?i)\\b(?:ticket|specialist|colleague)"), "ESCALATION");

    static final String ACTION_CLAIM_REPLACEMENT =
            "I haven't changed anything on your account; any change needs your confirmation first. ";

    /** Used once some effect of this conversation is done, so the rewrite does not deny it. */
    static final String PARTIAL_CLAIM_REPLACEMENT = "Only the steps shown as done in your requests have been carried"
            + " out; anything else still needs your confirmation or is being handled by our team. ";

    private final String canary;
    private final Set<String> allowedForms = new HashSet<>();
    private final Set<BigDecimal> allowedValues = new HashSet<>();
    private final Set<BigDecimal> customerValues = new HashSet<>();
    private final Set<BigDecimal> allowedExclGst = new HashSet<>();
    private final Set<String> doneEffects = new HashSet<>();

    GroundingGate(String canary) {
        this.canary = canary == null || canary.isBlank() ? null : canary.toLowerCase(Locale.ROOT);
    }

    /** Adds every amount string in a tool result or server context to the allowed set. */
    void allow(String toolOutput) {
        for (Token t : tokens(toolOutput)) {
            allowedForms.add(t.form());
            allowedValues.add(t.value());
            if (t.form().endsWith(" excl. GST")) {
                allowedExclGst.add(t.value());
            }
        }
    }

    /**
     * A goodwill amount argument must be an {@code excl. GST} amount from the allowed set (A-110;
     * actions.md §3.1): the model copies it, it never computes it.
     */
    boolean groundedExclGst(BigDecimal value) {
        return value != null && allowedExclGst.contains(value.stripTrailingZeros());
    }

    /**
     * Effects the BSS confirmed for this conversation's actions ({@code ActionEffect} names). A claim
     * that something was done passes only if every effect it names is here (actions.md §12).
     */
    void effectsDone(Set<String> effects) {
        doneEffects.addAll(effects);
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
        Optional<Set<String>> claimed = claimedEffects(sentence);
        if (claimed.isPresent() && !(claimed.get().size() > 0 && doneEffects.containsAll(claimed.get()))) {
            return new Result(Verdict.PASS,
                    doneEffects.isEmpty() ? ACTION_CLAIM_REPLACEMENT : PARTIAL_CLAIM_REPLACEMENT, true);
        }
        return new Result(Verdict.PASS, sentence, false);
    }

    /**
     * The effects a sentence claims were carried out, or empty if it claims none. A claim with a
     * generic verb and no recognisable noun claims an unknown effect (an empty set), which never passes.
     */
    static Optional<Set<String>> claimedEffects(String sentence) {
        Set<String> effects = new HashSet<>();
        boolean claim = false;
        for (Pattern p : List.of(FIRST_PERSON_ACTION, PASSIVE_ACTION)) {
            Matcher m = p.matcher(sentence);
            while (m.find()) {
                claim = true;
                String effect = VERB_EFFECT.get(m.group("verb").toLowerCase(Locale.ROOT));
                if (effect != null) {
                    effects.add(effect);
                }
                else {
                    NOUN_EFFECT.forEach((noun, e) -> {
                        if (noun.matcher(sentence).find()) {
                            effects.add(e);
                        }
                    });
                }
            }
        }
        return claim ? Optional.of(effects) : Optional.empty();
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
