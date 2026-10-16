package com.amateur.howmuchbackend.application.port.out.account;

import com.amateur.howmuchbackend.application.account.model.*;
import com.amateur.howmuchbackend.application.certificate.model.CertificateEnvelope;
import com.amateur.howmuchbackend.domain.account.*;

import java.time.LocalDateTime;
import java.util.*;

public interface CertificateReferenceOperationStorePort {
    Optional<CertificateReferenceOperation> find(UUID id, long userId);

    Optional<CertificateReferenceOperation> findByKey(long userId, UUID key);

    Optional<CertificateReferenceOperation> findActive(long userId);

    void create(CertificateReferenceOperation operation, CertificateEnvelope envelope);

    boolean receive(UUID messageId, UUID operationId, String hash, LocalDateTime now);

    Optional<CertificateReferenceExecution> claim(LocalDateTime now);

    boolean markProviderStarted(CertificateReferenceExecution execution, LocalDateTime now);

    boolean finish(
            CertificateReferenceExecution execution,
            CertificateReferenceStatus status,
            String failureCode,
            boolean alreadyConnected,
            LocalDateTime now);

    void defer(CertificateReferenceExecution execution, LocalDateTime now);

    Optional<CertificateReferencePublication> claimPublication(LocalDateTime now);

    void published(CertificateReferencePublication publication, LocalDateTime now);

    void publicationFailed(CertificateReferencePublication publication, LocalDateTime now);

    void recover(LocalDateTime now);
}
