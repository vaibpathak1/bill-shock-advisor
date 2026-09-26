package com.telco.billshock.actions.internal;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import com.telco.billshock.actions.ExecutionStep;
import com.telco.billshock.actions.ExecutionStep.StepStatus;
import com.telco.billshock.bss.BssRejectedException;

/**
 * Runs an action's steps in order (actions.md §6.2, §6.3). A {@link BssRejectedException} is a
 * definite rejection; <b>any other failure is an unknown outcome</b>, because the effect may have
 * been applied: the run stops and the action stays {@code EXECUTING} (A-112). Never called inside a
 * database transaction.
 */
@Component
class ExecutionRunner {

    private static final Logger log = LoggerFactory.getLogger(ExecutionRunner.class);

    private final Clock clock;

    ExecutionRunner(ObjectProvider<Clock> clock) {
        this.clock = clock.getIfAvailable(Clock::systemUTC);
    }

    enum Outcome {
        ALL_DONE, REJECTED, UNKNOWN
    }

    record Run(Outcome outcome, List<ExecutionStep> steps) {

        String references() {
            List<String> refs = steps.stream().map(ExecutionStep::reference).filter(r -> r != null).toList();
            return refs.isEmpty() ? null : String.join(",", refs);
        }

        String rejectionCode() {
            return steps.stream()
                .filter(s -> s.status() == StepStatus.REJECTED)
                .map(ExecutionStep::detail)
                .findFirst()
                .orElse(null);
        }
    }

    Run run(long actionId, List<ActionExecutor.Step> plan) {
        List<ExecutionStep> done = new ArrayList<>();
        for (ActionExecutor.Step step : plan) {
            try {
                String reference = step.call().get();
                done.add(new ExecutionStep(step.effect(), StepStatus.DONE, reference, null, clock.instant()));
            }
            catch (BssRejectedException e) {
                done.add(new ExecutionStep(step.effect(), StepStatus.REJECTED, null, e.code(), clock.instant()));
                return new Run(Outcome.REJECTED, done);
            }
            catch (RuntimeException e) {
                log.warn("Action {} step {}: outcome unknown ({})", actionId, step.effect(), e.getClass().getSimpleName());
                done.add(new ExecutionStep(step.effect(), StepStatus.UNKNOWN, null, e.getClass().getSimpleName(),
                        clock.instant()));
                return new Run(Outcome.UNKNOWN, done);
            }
        }
        return new Run(Outcome.ALL_DONE, done);
    }
}
