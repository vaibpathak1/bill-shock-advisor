package com.telco.billshock.tools;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import com.telco.billshock.actions.ActionRequest;

/**
 * {@code escalateToHuman} (SPEC §4.4, US-RES-06). Registered at every autonomy level, Level 0
 * included (A-109). It opens the hand-off ticket at once, without confirmation, at most once per
 * conversation (actions.md §6.5).
 */
@Component
public class EscalationTool {

    private final ActionTools actions;

    public EscalationTool(ActionTools actions) {
        this.actions = actions;
    }

    @Tool(name = "escalateToHuman", description = """
            Hands the conversation to a human customer care specialist with a short summary, so they do not need \
            to ask the same questions again. Use it when the customer asks for a person or the issue cannot be \
            resolved here. A ticket is opened at once; no confirmation is needed. Never put phone numbers or \
            other personal details in the summary.""")
    public ToolResult escalateToHuman(
            @ToolParam(description = "What was found so far, in two or three plain sentences") String summary,
            @ToolParam(description = "Why a human is needed, in one sentence") String reason,
            ToolContext toolContext) {
        return actions.propose(toolContext, new ActionRequest.Escalation(summary, reason));
    }
}
