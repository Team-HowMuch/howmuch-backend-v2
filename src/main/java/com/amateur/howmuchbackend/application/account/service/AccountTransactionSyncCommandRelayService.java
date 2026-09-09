package com.amateur.howmuchbackend.application.account.service;

import com.amateur.howmuchbackend.application.port.in.account.PublishAccountTransactionSyncCommandsUseCase;
import com.amateur.howmuchbackend.application.port.out.account.AccountTransactionSyncCommandOutboxPort;
import com.amateur.howmuchbackend.application.port.out.account.AccountTransactionSyncCommandPublisherPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Service
@RequiredArgsConstructor
public class AccountTransactionSyncCommandRelayService
        implements PublishAccountTransactionSyncCommandsUseCase {
    private final AccountTransactionSyncCommandOutboxPort outboxPort;
    private final AccountTransactionSyncCommandPublisherPort publisherPort;

    @Override
    public int publishReady(int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("동기화 command batch 크기는 1 이상이어야 합니다.");
        }
        int published = 0;
        while (published < limit) {
            var candidate = outboxPort.claimNextReady(LocalDateTime.now(ZoneOffset.UTC));
            if (candidate.isEmpty()) {
                break;
            }
            var command = candidate.get();
            publisherPort.publish(command);
            outboxPort.markPublished(
                    command.outboxId(),
                    command.attemptCount(),
                    LocalDateTime.now(ZoneOffset.UTC)
            );
            published++;
        }
        return published;
    }
}
