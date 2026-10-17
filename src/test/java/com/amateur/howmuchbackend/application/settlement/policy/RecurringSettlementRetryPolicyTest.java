package com.amateur.howmuchbackend.application.settlement.policy;

import com.amateur.howmuchbackend.application.port.out.settlement.RecurringSettlementExecutionPort.Execution;
import com.amateur.howmuchbackend.application.port.out.settlement.RecurringSettlementExecutionPort.State;
import com.amateur.howmuchbackend.support.RecurringSettlementFixtures;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RecurringSettlementRetryPolicyTest {
    private final RecurringSettlementRetryPolicy policy = new RecurringSettlementRetryPolicy();

    @Test
    void metadataBoundsAttemptsEvenIfFailureSummaryWasNotSaved() {
        var value = RecurringSettlementFixtures.template();
        assertThat(policy.canAutomaticallyLaunch(value,
                new Execution(State.FAILED, 3, true, true, value.nextRunAt()), value.nextRunAt().plusYears(1)))
                .isFalse();
        assertThat(policy.canAutomaticallyLaunch(value,
                new Execution(State.FAILED, 2, true, true, value.nextRunAt()), value.nextRunAt().plusMinutes(4)))
                .isFalse();
        assertThat(policy.canAutomaticallyLaunch(value,
                new Execution(State.FAILED, 2, true, true, value.nextRunAt()), value.nextRunAt().plusMinutes(5)))
                .isTrue();
    }

    @Test
    void runningStoppedUnknownCompletedAndManualFailures_areNotAutomaticallyRestarted() {
        var value = RecurringSettlementFixtures.template();
        for (var state : new State[]{State.RUNNING, State.STOPPED, State.UNKNOWN, State.COMPLETED}) {
            assertThat(policy.canAutomaticallyLaunch(value, new Execution(state, 0, true, false, null),
                    value.nextRunAt())).isFalse();
        }
        assertThat(policy.canAutomaticallyLaunch(value,
                new Execution(State.FAILED, 1, false, true, value.nextRunAt()),
                value.nextRunAt().plusYears(1))).isFalse();
    }
}
