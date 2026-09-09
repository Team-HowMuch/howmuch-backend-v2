package com.amateur.howmuchbackend.infra.account;

import com.amateur.howmuchbackend.application.port.in.account.PublishAccountTransactionSyncCommandsUseCase;
import com.amateur.howmuchbackend.infra.account.config.AccountTransactionSyncProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(
        prefix = "howmuch.account.transaction-sync.outbox",
        name = "enabled",
        havingValue = "true"
)
public class AccountTransactionSyncCommandScheduler {
    private final PublishAccountTransactionSyncCommandsUseCase useCase;
    private final AccountTransactionSyncProperties properties;

    @Scheduled(
            fixedDelayString = "${howmuch.account.transaction-sync.outbox.poll-interval:200ms}",
            initialDelayString = "${howmuch.account.transaction-sync.outbox.initial-delay:1s}"
    )
    public void poll() {
        try {
            int published = useCase.publishReady(properties.outbox().batchSize());
            if (published > 0) {
                log.info("Published account transaction sync commands. count={}", published);
            }
        } catch (RuntimeException exception) {
            log.error(
                    "Account transaction sync command polling failed. failureType={}",
                    exception.getClass().getSimpleName()
            );
        }
    }
}
