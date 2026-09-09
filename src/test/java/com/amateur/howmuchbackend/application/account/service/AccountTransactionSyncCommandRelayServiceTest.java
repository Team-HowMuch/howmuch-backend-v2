package com.amateur.howmuchbackend.application.account.service;

import com.amateur.howmuchbackend.application.port.out.account.AccountTransactionSyncCommandOutboxPort;
import com.amateur.howmuchbackend.application.port.out.account.AccountTransactionSyncCommandPublisherPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccountTransactionSyncCommandRelayServiceTest {
    @Mock private AccountTransactionSyncCommandOutboxPort outboxPort;
    @Mock private AccountTransactionSyncCommandPublisherPort publisherPort;

    @Test
    void publishesClaimedCommandAndMarksItsExactAttempt() {
        var command = command();
        when(outboxPort.claimNextReady(any())).thenReturn(Optional.of(command), Optional.empty());
        when(outboxPort.markPublished(eq(command.outboxId()), eq(command.attemptCount()), any()))
                .thenReturn(true);
        var service = new AccountTransactionSyncCommandRelayService(outboxPort, publisherPort);

        assertThat(service.publishReady(10)).isOne();

        verify(publisherPort).publish(command);
        verify(outboxPort).markPublished(eq(command.outboxId()), eq(command.attemptCount()), any());
    }

    @Test
    void doesNotMarkPublishedWhenKafkaPublishFails() {
        var command = command();
        when(outboxPort.claimNextReady(any())).thenReturn(Optional.of(command));
        var service = new AccountTransactionSyncCommandRelayService(outboxPort, ignored -> {
            throw new IllegalStateException("broker unavailable");
        });

        assertThatThrownBy(() -> service.publishReady(1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("broker unavailable");
        verify(outboxPort, never()).markPublished(any(), anyInt(), any());
    }

    private AccountTransactionSyncCommandOutboxPort.PendingCommand command() {
        return new AccountTransactionSyncCommandOutboxPort.PendingCommand(
                UUID.randomUUID(),
                1,
                LocalDateTime.of(2026, 9, 9, 3, 0),
                UUID.randomUUID(),
                UUID.randomUUID(),
                1,
                1,
                UUID.randomUUID(),
                LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 8, 31),
                LocalDateTime.of(2026, 9, 9, 3, 10)
        );
    }
}
