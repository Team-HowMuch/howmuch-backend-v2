package com.amateur.howmuchbackend.infra.codef.config;

import com.amateur.howmuchbackend.application.port.in.external.RunAdditionalAuthenticationWorkUseCase;

import lombok.RequiredArgsConstructor;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        prefix = "howmuch.codef.additional-auth",
        name = "enabled",
        havingValue = "true")
public class AdditionalAuthenticationScheduler {
    private final RunAdditionalAuthenticationWorkUseCase worker;

    @Scheduled(
            fixedDelayString = "${howmuch.codef.additional-auth.worker-delay-ms:1000}",
            scheduler = "financeAuthenticationScheduler")
    public void execute() {
        worker.executeOne();
    }

    @Scheduled(
            fixedDelayString = "${howmuch.codef.additional-auth.recovery-delay-ms:5000}",
            scheduler = "financeAuthenticationScheduler")
    public void recover() {
        worker.recover();
    }
}
