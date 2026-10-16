package com.amateur.howmuchbackend.infra.account.config;

import com.amateur.howmuchbackend.application.port.in.account.RunCertificateReferenceWorkUseCase;

import lombok.RequiredArgsConstructor;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        prefix = "howmuch.certificate-reference",
        name = "enabled",
        havingValue = "true")
public class CertificateReferenceWorkerScheduler {
    private final RunCertificateReferenceWorkUseCase worker;

    @Scheduled(
            fixedDelayString = "${howmuch.certificate-reference.worker-delay-ms:1000}",
            scheduler = "certificateReferenceExecutionScheduler")
    public void execute() {
        worker.executeOne();
    }

    @Scheduled(
            fixedDelayString = "${howmuch.certificate-reference.relay-delay-ms:500}",
            scheduler = "certificateReferenceRelayScheduler")
    public void publish() {
        worker.publishOne();
    }

    @Scheduled(
            fixedDelayString = "${howmuch.certificate-reference.recovery-delay-ms:10000}",
            scheduler = "certificateReferenceRelayScheduler")
    public void recover() {
        worker.recover();
    }
}
