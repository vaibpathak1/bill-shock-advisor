package com.telco.billshock.actions.internal;

import java.util.List;
import java.util.function.Supplier;

import com.telco.billshock.actions.ActionEffect;
import com.telco.billshock.actions.ActionType;

/**
 * Strategy per action type (SPEC §4.2; actions.md §6.2): the BSS calls that carry an approved
 * action out, in order. Every call uses the action's key {@code pa-{actionId}}, so re-running the
 * steps after an unknown outcome is safe (A-112).
 */
interface ActionExecutor {

    boolean supports(ActionType type);

    List<Step> steps(ActionRow action);

    /** @param call performs the BSS call and returns its receipt reference */
    record Step(ActionEffect effect, Supplier<String> call) {
    }
}
