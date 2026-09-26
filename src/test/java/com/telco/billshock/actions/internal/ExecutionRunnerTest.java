package com.telco.billshock.actions.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.support.StaticListableBeanFactory;

import com.telco.billshock.actions.ActionEffect;
import com.telco.billshock.actions.ExecutionStep;
import com.telco.billshock.actions.ExecutionStep.StepStatus;
import com.telco.billshock.bss.BssRejectedException;
import com.telco.billshock.bss.BssUnavailableException;

/** Only a definite rejection is a failure; anything else is an unknown outcome (actions.md §6.3, A-112). */
class ExecutionRunnerTest {

    private final ExecutionRunner runner = new ExecutionRunner(
            new StaticListableBeanFactory().getBeanProvider(Clock.class));
    private final List<String> called = new ArrayList<>();

    private ActionExecutor.Step step(ActionEffect effect, RuntimeException failure) {
        return new ActionExecutor.Step(effect, () -> {
            called.add(effect.name());
            if (failure != null) {
                throw failure;
            }
            return "REF-" + effect;
        });
    }

    @Test
    void everyStepDone() {
        ExecutionRunner.Run run = runner.run(1,
                List.of(step(ActionEffect.UNSUBSCRIBE, null), step(ActionEffect.REFUND, null)));

        assertThat(run.outcome()).isEqualTo(ExecutionRunner.Outcome.ALL_DONE);
        assertThat(run.references()).isEqualTo("REF-UNSUBSCRIBE,REF-REFUND");
    }

    @Test
    void aDefiniteRejectionStopsAndKeepsTheDoneStep() {
        ExecutionRunner.Run run = runner.run(1, List.of(step(ActionEffect.UNSUBSCRIBE, null),
                step(ActionEffect.REFUND, new BssRejectedException("NOT_REFUNDABLE", "no"))));

        assertThat(run.outcome()).isEqualTo(ExecutionRunner.Outcome.REJECTED);
        assertThat(run.steps()).extracting(ExecutionStep::effect, ExecutionStep::status)
            .containsExactly(tuple(ActionEffect.UNSUBSCRIBE, StepStatus.DONE), tuple(ActionEffect.REFUND, StepStatus.REJECTED));
        assertThat(run.rejectionCode()).isEqualTo("NOT_REFUNDABLE");
    }

    @Test
    void aTimeoutOrAnyOtherFailureIsUnknownNeverRejected() {
        for (RuntimeException failure : List.of(new BssUnavailableException("timeout"),
                new IllegalStateException("connection reset"))) {
            called.clear();
            ExecutionRunner.Run run = runner.run(1,
                    List.of(step(ActionEffect.CREDIT, failure), step(ActionEffect.REFUND, null)));

            assertThat(run.outcome()).isEqualTo(ExecutionRunner.Outcome.UNKNOWN);
            assertThat(run.steps()).singleElement().extracting(ExecutionStep::status).isEqualTo(StepStatus.UNKNOWN);
            assertThat(called).containsExactly("CREDIT"); // nothing runs after an unknown step
        }
    }
}
