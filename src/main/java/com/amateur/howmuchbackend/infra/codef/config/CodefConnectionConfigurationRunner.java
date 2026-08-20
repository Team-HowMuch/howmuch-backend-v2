package com.amateur.howmuchbackend.infra.codef.config;

import com.amateur.howmuchbackend.application.port.out.account.AccountStorePort;
import com.amateur.howmuchbackend.application.port.out.external.ExternalConnectionStorePort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class CodefConnectionConfigurationRunner implements ApplicationRunner {
    private final ExternalConnectionStorePort connectionStorePort;
    private final AccountStorePort accountStorePort;
    private final CodefConfigurationFingerprint configurationFingerprint;

    @Override
    public void run(ApplicationArguments args) {
        int deactivatedConnections = connectionStorePort.deactivateIncompatibleConnections(
                configurationFingerprint.current()
        );
        int deactivatedAccounts = accountStorePort.deactivateAccountsForInactiveConnections();
        if (deactivatedConnections > 0 || deactivatedAccounts > 0) {
            log.info(
                    "Deactivated {} CODEF connection(s) and {} account(s) because the configuration changed.",
                    deactivatedConnections,
                    deactivatedAccounts
            );
        }
    }
}
