package com.amateur.howmuchbackend.application.external.service;

import com.amateur.howmuchbackend.application.port.out.certificate.*;
import com.amateur.howmuchbackend.application.port.out.external.*;

import lombok.RequiredArgsConstructor;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.*;
import java.util.Arrays;

@lombok.extern.slf4j.Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(
        prefix = "howmuch.codef.additional-auth",
        name = "enabled",
        havingValue = "true")
public class AdditionalAuthenticationWorker
        implements com.amateur.howmuchbackend.application.port.in.external
                .RunAdditionalAuthenticationWorkUseCase {
    private final AdditionalAuthenticationExecutionService transactions;
    private final AdditionalAuthenticationStorePort store;
    private final AdditionalAuthenticationProtectorPort protector;
    private final CodefAdditionalAuthenticationPort provider;
    private final CertificateUploadStorePort uploads;
    private final CertificateSecretProtectionPort certificates;
    private final Clock clock;

    public void executeOne() {
        transactions
                .claim()
                .ifPresent(
                        c -> {
                            byte[] context = null;
                            boolean started = false;
                            try {
                                context = protector.openAuthentication(c);
                                if (!c.certificate()) {
                                    if (!transactions.start(c)) return;
                                    started = true;
                                    var result = provider.resumeOperation(context);
                                    transactions.completeOperation(c, result);
                                    return;
                                }
                                var command = c.execution().command();
                                try (var material =
                                        certificates.openUpload(
                                                c.sessionId(),
                                                command.uploadRef(),
                                                uploads.load(
                                                        command.uploadRef(),
                                                        LocalDateTime.now(clock)))) {
                                    if (!transactions.start(c)) return;
                                    started = true;
                                    var result = provider.resume(context);
                                    transactions.complete(c, material, result);
                                }
                            } catch (
                                    com.amateur.howmuchbackend.application.external.model
                                                    .AdditionalAuthenticationRequiredException
                                            required) {
                                transactions.requestInput(c, required.request());
                            } catch (
                                    com.amateur.howmuchbackend.application.external.model
                                                    .AdditionalAuthenticationRejectedException
                                            rejected) {
                                transactions.reject(c, rejected.getStatusCode().getCode());
                            } catch (RuntimeException failure) {
                                log.warn(
                                        "CODEF SMS continuation failed. challengeId={}"
                                                + " requestStarted={} failureType={}",
                                        c.id(),
                                        started,
                                        failure.getClass().getSimpleName());
                                transactions.fail(c, started);
                            } finally {
                                if (context != null) Arrays.fill(context, (byte) 0);
                            }
                        });
    }

    public void recover() {
        store.clearExpiredResults(LocalDateTime.now(clock));
        for (var id : store.recoverable(LocalDateTime.now(clock))) transactions.recover(id);
    }
}
