package com.amateur.howmuchbackend.application.port.out.certificate;

import com.amateur.howmuchbackend.application.certificate.model.*;
import com.amateur.howmuchbackend.application.port.in.certificate.command.ReceiveCertificateCommand;

import java.time.LocalDateTime;
import java.util.*;

public interface CertificateExecutionStorePort {
    boolean receive(ReceiveCertificateCommand command, String hash, LocalDateTime now);

    Optional<CertificateExecution> claim(LocalDateTime now);

    boolean startRequest(CertificateExecution execution, LocalDateTime now);

    void retry(CertificateExecution execution, LocalDateTime now);

    void finish(
            CertificateExecution execution,
            String status,
            String failureCode,
            UUID connectionRef,
            CertificateEnvelope connection,
            LocalDateTime now);

    CertificateCleanupTarget cleanupTarget(CertificateExecution execution, LocalDateTime now);

    boolean startCleanup(CertificateExecution execution, UUID connectionRef, LocalDateTime now);

    void finishCleanup(CertificateExecution execution, boolean deleted, LocalDateTime now);

    void recover(LocalDateTime now);
}
