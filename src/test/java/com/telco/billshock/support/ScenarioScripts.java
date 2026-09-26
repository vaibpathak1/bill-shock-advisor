package com.telco.billshock.support;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import com.telco.billshock.support.FakeAnthropicApi.Script;
import com.telco.billshock.support.FakeAnthropicApi.ToolUse;

/**
 * The scripted model answers for the six seed scenarios (actions.md §8.2, §8.3), shared by the
 * scenario E2E tests and the scripted demo, so the demo shows exactly what the tests check. Only
 * the model's words and tool choices are scripted: tools, gates, guardrails, the workflow and the
 * mock BSS all run for real, and every amount in these texts must pass the grounding gate.
 *
 * <p>{@link #select} picks the scenario from the request (each seed account's current bill total is
 * unique and is in the pre-fetch context), the turn from the number of earlier answers, and the
 * round from the tool rounds of the current turn.
 */
public final class ScenarioScripts {

    public static final String UNKNOWN_SCENARIO = "This scripted demo only covers the six seed scenarios. Sign in as"
            + " one of the demo customers and ask why the bill is so high.";

    public static final String NO_MORE_TURNS = "This scripted demo has no further answers in this conversation."
            + " Start a new chat to try it again.";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** The current (September) bill total of each seed account, as the pre-fetch shows it. */
    private static final Map<String, Integer> ACCOUNT_BY_TOTAL = Map.of("₹2,919.32 incl. GST", 1001,
            "₹905.82 incl. GST", 1002, "₹854.90 incl. GST", 1003, "₹642.12 incl. GST", 1004, "₹1,413.64 incl. GST",
            1005, "₹617.14 incl. GST", 1006);

    /** Scripts per account: turns, each a list of rounds. Fresh objects per call. */
    private static final Map<Integer, List<List<Supplier<Script>>>> SCRIPTS = Map.of(
            1001, List.of(List.of(
                    () -> Script.tools("Let me compare the roaming packs for your trip.",
                            ToolUse.of("toolu_s1_1", "simulatePlans")),
                    () -> Script.tools("Your September bill is higher mainly because of international roaming charges"
                            + " of ₹2,094.50 incl. GST from your trip to the UAE. The IR_GCC_7D roaming pack would have"
                            + " covered the whole trip and saved ₹1,033.68 incl. GST. As a goodwill gesture, I have"
                            + " proposed a credit as if the pack had been active; it needs your confirmation and a"
                            + " supervisor's review.",
                            new ToolUse("toolu_s1_2", "proposeGoodwillCredit", """
                                    {"amountExclGst":"876.00","reason":"The IR_GCC_7D pack would have covered the whole trip.",\
                                    "lineItemIds":[1001260902,1001260903,1001260904]}""")),
                    () -> Script.text("Before your next trip to the Gulf, add IR_GCC_7D so roaming is covered."))),
            1002, List.of(List.of(
                    () -> Script.tools("Let me check whether another plan fits your data use.",
                            ToolUse.of("toolu_s2_1", "simulatePlans")),
                    () -> Script.tools("Your bill is higher because you used more data than your plan includes. On"
                            + " PP_499 this bill would have been ₹588.82 incl. GST, a saving of ₹317.00 incl. GST.",
                            new ToolUse("toolu_s2_2", "proposePlanChange", """
                                    {"planCode":"PP_499","effective":"NEXT_CYCLE"}""")),
                    () -> Script.text("I have proposed the move to PP_499 from your next bill cycle; it needs your"
                            + " confirmation."))),
            1003, List.of(List.of(
                    () -> Script.tools("Let me look at your subscriptions.",
                            ToolUse.of("toolu_s3_1", "getActiveSubscriptions")),
                    () -> Script.tools("Your bill includes Astro Daily, a third-party value-added service (VAS) that"
                            + " started on 15 August with no confirmation of your consent (no double opt-in).",
                            new ToolUse("toolu_s3_2", "proposeVasUnsubscribe", """
                                    {"subscriptionId":"SUB-1003-VAS-ASTRO","requestRefund":true}"""),
                            ToolUse.of("toolu_s3_3", "proposeThirdPartyBarring")),
                    () -> Script.text("I have set up two requests for you to confirm: unsubscribing from Astro Daily"
                            + " with a refund of ₹231.28 incl. GST, and blocking third-party charges. Cricket Scores"
                            + " has a complete opt-in record, so it stays.")),
                    // Turn 2: the claim gate lets this through only for effects the BSS confirmed.
                    List.of(() -> Script.text("Astro Daily has been cancelled and ₹231.28 incl. GST has been refunded"
                            + " to your account."))),
            1004, List.of(List.of(
                    () -> Script.tools("Let me look at the charges.", ToolUse.of("toolu_s4_1", "getLineItems")),
                    () -> Script.text("Your September bill has two part-month rentals because you moved from PP_399 to"
                            + " PP_699 on 1 September. This is expected, and nothing needs to change."))),
            1005, List.of(List.of(
                    () -> Script.tools("Let me look at the charges.", ToolUse.of("toolu_s5_1", "getLineItems")),
                    () -> Script.tools("Your September bill charges the PP_599 monthly rental twice for the same"
                            + " period, which is a billing error.",
                            new ToolUse("toolu_s5_2", "raiseDispute", """
                                    {"lineItemIds":[1005260902],"reason":"The monthly rental was charged twice."}""")),
                    () -> Script.text("I have prepared a dispute of ₹706.82 incl. GST for the extra charge; it goes to"
                            + " our billing team once you confirm."))),
            1006, List.of(List.of(
                    () -> Script.text("Your September bill is in line with your recent bills, so there is nothing to"
                            + " change."))));

    private ScenarioScripts() {
    }

    /** The scripted answer to one Messages API request. */
    public static Script select(String requestBody) {
        Integer account = ACCOUNT_BY_TOTAL.entrySet()
            .stream()
            .filter(e -> requestBody.contains(e.getKey()))
            .map(Map.Entry::getValue)
            .findFirst()
            .orElse(null);
        if (account == null) {
            return Script.text(UNKNOWN_SCENARIO);
        }
        JsonNode messages = JSON.readTree(requestBody).get("messages");
        int turnStart = 0;
        for (int i = 0; i < messages.size(); i++) {
            JsonNode m = messages.get(i);
            if ("user".equals(m.get("role").asString()) && !hasToolResult(m)) {
                turnStart = i;
            }
        }
        int turn = 0;
        int round = 0;
        for (int i = 0; i < messages.size(); i++) {
            if ("assistant".equals(messages.get(i).get("role").asString())) {
                if (i < turnStart) {
                    turn++;
                }
                else {
                    round++;
                }
            }
        }
        List<List<Supplier<Script>>> turns = SCRIPTS.get(account);
        if (turn >= turns.size() || round >= turns.get(turn).size()) {
            return Script.text(NO_MORE_TURNS);
        }
        return turns.get(turn).get(round).get();
    }

    private static boolean hasToolResult(JsonNode message) {
        JsonNode content = message.get("content");
        if (content == null || !content.isArray()) {
            return false;
        }
        for (JsonNode block : content) {
            if ("tool_result".equals(block.path("type").asString())) {
                return true;
            }
        }
        return false;
    }
}
