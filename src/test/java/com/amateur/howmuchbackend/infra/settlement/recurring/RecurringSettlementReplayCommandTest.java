package com.amateur.howmuchbackend.infra.settlement.recurring;

import com.amateur.howmuchbackend.application.port.in.settlement.ReplayRecurringSettlementUseCase;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.context.ConfigurableApplicationContext;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class RecurringSettlementReplayCommandTest {
    @Test
    void explicitReplay_usesOriginalUtcSlotAndRequiresExplicitRecoveryConfirmation() {
        var replay = mock(ReplayRecurringSettlementUseCase.class);
        var context = mock(ConfigurableApplicationContext.class);
        var command = new RecurringSettlementReplayCommand(replay, context);
        UUID id = UUID.randomUUID();
        command.run(new DefaultApplicationArguments("--recurring-replay-id=" + id,
                "--recurring-replay-scheduled-for=2026-10-31T00:00:00Z"));
        verify(replay).replay(id, LocalDateTime.of(2026, 10, 31, 0, 0), false);
        verify(context).close();
    }

    @Test
    void missingOrRepeatedReplayArguments_areRejectedBeforeExecution() {
        var replay = mock(ReplayRecurringSettlementUseCase.class);
        var context = mock(ConfigurableApplicationContext.class);
        var command = new RecurringSettlementReplayCommand(replay, context);
        assertThatThrownBy(() -> command.run(new DefaultApplicationArguments()))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(replay, context);
    }
}
