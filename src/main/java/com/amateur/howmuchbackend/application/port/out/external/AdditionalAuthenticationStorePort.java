package com.amateur.howmuchbackend.application.port.out.external;

import com.amateur.howmuchbackend.application.certificate.model.*;
import com.amateur.howmuchbackend.application.external.model.*;

import java.time.LocalDateTime;
import java.util.*;

public interface AdditionalAuthenticationStorePort {
    boolean tryAcquireOperationAdmission(long user, String organization);

    void create(
            UUID id,
            long user,
            CertificateExecution execution,
            AdditionalAuthenticationRequest request,
            CertificateEnvelope payload,
            LocalDateTime expiresAt,
            LocalDateTime now);

    Optional<AdditionalAuthenticationChallenge> find(UUID id, boolean lock);

    Optional<AdditionalAuthenticationChallenge> current(UUID sessionId);

    default List<AdditionalAuthenticationChallenge> activeForUser(long user, LocalDateTime now) {
        return List.of();
    }

    default void createOperation(
            UUID id,
            long user,
            UUID operationId,
            com.amateur.howmuchbackend.application.external.model.FinanceOperation operation,
            AdditionalAuthenticationRequest request,
            CertificateEnvelope payload,
            LocalDateTime expires,
            LocalDateTime now) {
        throw new UnsupportedOperationException();
    }

    default Optional<AdditionalAuthenticationChallenge> pending(
            long user, String organization, LocalDateTime now) {
        return Optional.empty();
    }

    default Optional<AdditionalAuthenticationChallenge> recentResult(
            long user, Long connection, String organization, LocalDateTime now) {
        return Optional.empty();
    }

    default Optional<CertificateEnvelope> result(UUID id, LocalDateTime now) {
        return Optional.empty();
    }

    default void finishWithResult(
            AdditionalAuthenticationChallenge c, CertificateEnvelope result, LocalDateTime now) {
        throw new UnsupportedOperationException();
    }

    void submit(UUID id, UUID submissionId, CertificateEnvelope accepted, LocalDateTime now);

    void createRetry(
            AdditionalAuthenticationChallenge previous,
            UUID id,
            AdditionalAuthenticationRequest request,
            CertificateEnvelope payload,
            LocalDateTime expiresAt,
            LocalDateTime now);

    Optional<AdditionalAuthenticationChallenge> claim(LocalDateTime now);

    boolean start(AdditionalAuthenticationChallenge challenge, LocalDateTime now);

    void finish(
            AdditionalAuthenticationChallenge challenge,
            String status,
            String code,
            LocalDateTime now);

    void cancelForSession(UUID session, LocalDateTime now);

    List<UUID> recoverable(LocalDateTime now);

    default void clearExpiredResults(LocalDateTime now) {}

    void requeue(AdditionalAuthenticationChallenge challenge, LocalDateTime now);
}
