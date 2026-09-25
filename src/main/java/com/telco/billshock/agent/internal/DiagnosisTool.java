package com.telco.billshock.agent.internal;

import java.util.List;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import com.telco.billshock.security.CurrentCustomer;

/**
 * The reporting tool that delivers the structured {@code BillShockDiagnosis} while the text
 * streams (llm-architecture.md §4). It only records the model's input; the diagnosis gate
 * checks every amount against the engine before anything is stored (agent.md §8.2).
 */
@Component
public class DiagnosisTool {

    static final String RECORDED = "{\"status\":\"RECORDED\"}";

    @Tool(name = "recordDiagnosis", description = """
            Records the structured diagnosis of the latest bill after you have explained it. Call it once, \
            at the end. Copy every amount exactly from the diffBills result: each cause's amountInclGst \
            display and the totalExcess display. Use an empty causes list when the bill is normal.""")
    public String recordDiagnosis(
            @ToolParam(description = "Causes of the increase, largest first; empty when the bill is normal") List<CauseInput> causes,
            @ToolParam(description = "The totalExcess display string from diffBills, copied exactly") String totalExcess,
            @ToolParam(description = "HIGH, MEDIUM or LOW") String confidence,
            @ToolParam(required = false, description = "At most 3 recommended next steps") List<ActionInput> recommendedActions,
            ToolContext toolContext) {
        CurrentCustomer.require();
        TurnState turn = (TurnState) toolContext.getContext().get(TurnState.KEY);
        turn.record(new TurnState.LlmDiagnosis(causes == null ? List.of() : causes, totalExcess, confidence,
                recommendedActions == null ? List.of() : recommendedActions));
        return RECORDED;
    }

    /**
     * @param group PLAN, DATA, VOICE, SMS, ROAMING, VAS or OTHER
     * @param amountInclGst the cause's amountInclGst display string, copied exactly
     */
    public record CauseInput(
            @ToolParam(description = "PLAN, DATA, VOICE, SMS, ROAMING, VAS or OTHER") String group,
            @ToolParam(description = "The cause's amountInclGst display string, copied exactly") String amountInclGst) {
    }

    /**
     * @param type PLAN_CHANGE, ADD_ON, VAS_UNSUBSCRIBE, THIRD_PARTY_BARRING, DISPUTE, GOODWILL_CREDIT or NO_ACTION
     * @param code the plan or add-on code for PLAN_CHANGE and ADD_ON
     */
    public record ActionInput(
            @ToolParam(description = "PLAN_CHANGE, ADD_ON, VAS_UNSUBSCRIBE, THIRD_PARTY_BARRING, DISPUTE, GOODWILL_CREDIT or NO_ACTION") String type,
            @ToolParam(required = false, description = "Plan or add-on code for PLAN_CHANGE and ADD_ON") String code,
            @ToolParam(description = "One plain sentence for the customer") String summary) {
    }
}
