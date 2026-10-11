package com.amateur.howmuchbackend.infra.certificate.config;

import com.amateur.howmuchbackend.application.port.in.certificate.RunCertificateWorkUseCase;

import lombok.RequiredArgsConstructor;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "howmuch.certificate", name = "enabled", havingValue = "true")
public class CertificateWorkerScheduler {
    private final RunCertificateWorkUseCase worker;

    @Scheduled(
            fixedDelayString = "${howmuch.certificate.worker-delay-ms:1000}",
            scheduler = "certificateExecutionScheduler")
    public void execute() {
        worker.executeOne();
    }

    @Scheduled(
            fixedDelayString = "${howmuch.certificate.relay-delay-ms:500}",
            scheduler = "certificateRelayScheduler")
    public void publish() {
        worker.publishOne(false);
        worker.publishOne(true);
    }

    @Scheduled(
            fixedDelayString = "${howmuch.certificate.recovery-delay-ms:10000}",
            scheduler = "certificateRelayScheduler")
    public void recover() {
        worker.recover();
    }
}
