package com.amateur.howmuchbackend.application.external.service;

import static com.amateur.howmuchbackend.domain.external.status.AdditionalAuthenticationStatusCode.*;

import com.amateur.howmuchbackend.application.certificate.model.*;
import com.amateur.howmuchbackend.application.certificate.service.CertificateRegistrationTransactions;
import com.amateur.howmuchbackend.application.external.model.*;
import com.amateur.howmuchbackend.application.port.in.external.*;
import com.amateur.howmuchbackend.application.port.in.external.command.SubmitAdditionalAuthenticationSmsCommand;
import com.amateur.howmuchbackend.application.port.in.external.result.AdditionalAuthenticationResult;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.application.port.out.certificate.*;
import com.amateur.howmuchbackend.application.port.out.external.*;
import com.amateur.howmuchbackend.common.exception.ApplicationException;

import lombok.RequiredArgsConstructor;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.*;

@Service
@RequiredArgsConstructor
@ConditionalOnProperty(
        prefix = "howmuch.codef.additional-auth",
        name = "enabled",
        havingValue = "true")
public class AdditionalAuthenticationService
        implements ListAdditionalAuthenticationsUseCase,
                GetAdditionalAuthenticationUseCase,
                SubmitAdditionalAuthenticationSmsUseCase,
                CancelAdditionalAuthenticationUseCase {
    private final AdditionalAuthenticationStorePort store;
    private final AdditionalAuthenticationProtectorPort protector;
    private final CodefAdditionalAuthenticationPort provider;
    private final CertificatePairingStorePort pairings;
    private final CertificateSessionStorePort sessions;
    private final CertificateRegistrationTransactions registrations;
    private final CurrentActorPort actor;
    private final CertificateRequestLimitPort limits;
    private final CertificateCapabilityPort capabilities;
    private final Clock clock;
    private final com.amateur.howmuchbackend.application.notification.service.NotificationRecorder
            notifications;

    private void notifyRequired(UUID id, long user, LocalDateTime now) {
        notifications.record(
                new com.amateur.howmuchbackend.domain.notification.UserNotification(
                        UUID.randomUUID(),
                        user,
                        null,
                        com.amateur.howmuchbackend.domain.notification.NotificationType
                                .FINANCE_AUTH_REQUIRED,
                        null,
                        "얼마",
                        "금융사 추가 인증이 필요해요",
                        "인증번호를 입력해 계좌 연결 또는 조회를 이어가세요.",
                        "FINANCE_SMS:" + id,
                        null,
                        now,
                        new com.amateur.howmuchbackend.domain.notification.NotificationTarget(
                                com.amateur.howmuchbackend.domain.notification
                                        .NotificationTargetType.FINANCE_AUTHENTICATION,
                                id.toString())));
    }

    @Transactional(readOnly = true)
    public java.util.List<AdditionalAuthenticationResult> list() {
        return store.activeForUser(actor.getCurrentActor().userId(), now()).stream()
                .map(this::result)
                .toList();
    }

    @Transactional
    public boolean suspend(CertificateExecution e, AdditionalAuthenticationRequest request) {
        var p = pairings.findBySession(e.command().sessionId()).orElse(null);
        var now = now();
        if (p == null
                || p.userId() == null
                || p.cancelledAt() != null
                || !p.expiresAt().isAfter(now)) return false;
        LocalDateTime expires = now.plusSeconds(Math.min(170, request.timeoutSeconds()));
        if (request.providerExpiresAt() != null && request.providerExpiresAt().isBefore(expires))
            expires = request.providerExpiresAt();
        if (e.command().deadline().isBefore(expires)) expires = e.command().deadline();
        if (p.expiresAt().isBefore(expires)) expires = p.expiresAt();
        if (!expires.isAfter(now)) return false;
        UUID id = UUID.randomUUID();
        byte[] context = request.payload();
        try {
            var encrypted =
                    protector.sealAuthentication(
                            id, p.userId(), e.command().sessionId(), 1, context);
            store.create(id, p.userId(), e, request, encrypted, expires, now);
            notifyRequired(id, p.userId(), now);
            return true;
        } finally {
            Arrays.fill(context, (byte) 0);
        }
    }

    @Transactional(
            propagation = org.springframework.transaction.annotation.Propagation.MANDATORY,
            noRollbackFor = AdditionalAuthenticationPendingException.class)
    public void assertNoPending(long user, String organization) {
        if (!store.tryAcquireOperationAdmission(user, organization))
            throw ApplicationException.of(FINANCE_AUTHENTICATION_CONFLICT);
        store.pending(user, organization, now())
                .ifPresent(
                        c -> {
                            throw new AdditionalAuthenticationPendingException(c.id());
                        });
    }

    @Transactional(noRollbackFor = AdditionalAuthenticationPendingException.class)
    public java.util.List<BankConnectionPort.ExternalAccountCandidate> withAccountQueryAdmission(
            long user,
            String organization,
            java.util.function.Supplier<java.util.List<BankConnectionPort.ExternalAccountCandidate>> query) {
        assertNoPending(user, organization);
        return query.get();
    }

    @Transactional(noRollbackFor = AdditionalAuthenticationPendingException.class)
    public java.util.List<com.amateur.howmuchbackend.application.port.out.external.BankTransactionPort.BankTransaction>
            withTransactionQueryAdmission(long user, String organization,
                    java.util.function.Supplier<java.util.List<com.amateur.howmuchbackend.application.port.out.external.BankTransactionPort.BankTransaction>> query) {
        assertNoPending(user, organization);
        return query.get();
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public boolean tryAdmitInitialOperation(long user, String organization) {
        return store.tryAcquireOperationAdmission(user, organization)
                && store.pending(user, organization, now()).isEmpty();
    }

    @Transactional
    public UUID suspendOperation(
            long user, FinanceOperation operation, AdditionalAuthenticationRequest request) {
        if (!store.tryAcquireOperationAdmission(user, operation.organization())
                || store.pending(user, operation.organization(), now()).isPresent())
            throw ApplicationException.of(FINANCE_AUTHENTICATION_CONFLICT);
        UUID id = UUID.randomUUID(), operationId = UUID.randomUUID();
        LocalDateTime expires = now().plusSeconds(Math.min(170, request.timeoutSeconds()));
        if (request.providerExpiresAt() != null && request.providerExpiresAt().isBefore(expires))
            expires = request.providerExpiresAt();
        if (!expires.isAfter(now())) throw ApplicationException.of(FINANCE_AUTHENTICATION_EXPIRED);
        byte[] raw = request.payload(), context = null;
        try {
            context = provider.prepareOperation(raw, operation);
            store.createOperation(
                    id,
                    user,
                    operationId,
                    operation,
                    request,
                    protector.sealAuthentication(id, user, operationId, 1, context),
                    expires,
                    now());
            notifyRequired(id, user, now());
            return id;
        } finally {
            Arrays.fill(raw, (byte) 0);
            if (context != null) Arrays.fill(context, (byte) 0);
        }
    }

    @Transactional(readOnly = true)
    public java.util.Optional<FinanceAuthenticationCompletion> cachedAccounts(
            long user, Long connection, String organization) {
        return store.recentResult(user, connection, organization, now()).flatMap(this::completion);
    }

    private Optional<FinanceAuthenticationCompletion> completion(
            AdditionalAuthenticationChallenge c) {
        return store.result(c.id(), now())
                .map(
                        envelope -> {
                            byte[] bytes = protector.openResult(c, envelope);
                            try {
                                return provider.decodeCompletion(bytes);
                            } finally {
                                Arrays.fill(bytes, (byte) 0);
                            }
                        });
    }

    @Transactional(readOnly = true)
    public AdditionalAuthenticationResult get(UUID id) {
        return result(owned(id, false));
    }

    @Transactional
    public AdditionalAuthenticationResult submit(SubmitAdditionalAuthenticationSmsCommand command) {
        var c = owned(command.challengeId(), true);
        if (c.submissionId() != null) {
            if (!c.submissionId().equals(command.submissionId()))
                throw ApplicationException.of(FINANCE_AUTHENTICATION_CONFLICT);
            return result(c);
        }
        if (!c.expiresAt().isAfter(now()))
            throw ApplicationException.of(FINANCE_AUTHENTICATION_EXPIRED);
        if (!c.status().equals("WAITING_INPUT") || c.cancelRequested())
            throw ApplicationException.of(FINANCE_AUTHENTICATION_CONFLICT);
        if (!limits.acquire(capabilities.hash("codef-sms:" + c.userId())))
            throw ApplicationException.of(
                    com.amateur.howmuchbackend.domain.certificate.CertificateStatusCode
                            .CERTIFICATE_RATE_LIMITED);
        if (command.submissionId() == null)
            throw ApplicationException.of(FINANCE_AUTHENTICATION_INPUT_INVALID);
        if (c.certificate()) {
            var s =
                    sessions.find(c.sessionId(), false)
                            .orElseThrow(
                                    () ->
                                            ApplicationException.of(
                                                    FINANCE_AUTHENTICATION_NOT_FOUND));
            var p =
                    pairings.findBySession(c.sessionId())
                            .orElseThrow(
                                    () -> ApplicationException.of(FINANCE_AUTHENTICATION_CONFLICT));
            if (!s.status().equals("REGISTERING")
                    || !s.expiresAt().isAfter(now())
                    || p.cancelledAt() != null
                    || !p.expiresAt().isAfter(now()))
                throw ApplicationException.of(FINANCE_AUTHENTICATION_CONFLICT);
        }
        byte[] context = protector.openAuthentication(c);
        byte[] accepted = null;
        try {
            accepted = provider.acceptSms(context, command.code());
            store.submit(
                    c.id(),
                    command.submissionId(),
                    protector.sealAuthentication(
                            c.id(), c.userId(), c.sessionId(), c.generation(), accepted),
                    now());
        } finally {
            Arrays.fill(context, (byte) 0);
            if (accepted != null) Arrays.fill(accepted, (byte) 0);
        }
        return result(store.find(c.id(), false).orElseThrow());
    }

    @Transactional
    public AdditionalAuthenticationResult cancel(UUID id) {
        var c = owned(id, true);
        if ("FAILED".equals(c.status()) && "AUTH_SMS_RETRY_REQUIRED".equals(c.failureCode())) {
            var latest = store.current(c.sessionId());
            if (latest.isPresent() && latest.get().generation() > c.generation())
                c = owned(latest.get().id(), true);
        }
        if (Set.of("SUCCEEDED", "FAILED", "UNKNOWN", "CANCELLED", "EXPIRED").contains(c.status()))
            return result(c);
        if (!c.certificate()) {
            store.cancelForSession(c.sessionId(), now());
            return result(store.find(id, false).orElseThrow());
        }
        // Same cancellation boundary as the mobile pairing; no separate anonymous cancellation.
        var p =
                pairings.findBySession(c.sessionId())
                        .orElseThrow(
                                () -> ApplicationException.of(FINANCE_AUTHENTICATION_NOT_FOUND));
        registrations.cancelPairedForUser(p.id(), c.userId());
        pairings.cancel(p.id(), now());
        store.cancelForSession(c.sessionId(), now());
        return result(store.find(id, false).orElseThrow());
    }

    @Transactional(readOnly = true)
    public AdditionalAuthenticationResult forSession(UUID session) {
        if (session == null) return null;
        return store.current(session).map(this::result).orElse(null);
    }

    private AdditionalAuthenticationChallenge owned(UUID id, boolean lock) {
        long user = actor.getCurrentActor().userId();
        var c =
                store.find(id, false)
                        .filter(v -> v.userId() == user)
                        .orElseThrow(
                                () -> ApplicationException.of(FINANCE_AUTHENTICATION_NOT_FOUND));
        // Existing certificate cancellation and completion also lock the session first.
        if (lock && c.certificate()) {
            var pairing = pairings.findBySession(c.sessionId());
            if (pairing.isPresent()) pairings.find(pairing.get().id(), true);
            sessions.find(c.sessionId(), true);
            c = store.find(id, true).orElseThrow();
        }
        if (lock && !c.certificate()) c = store.find(id, true).orElseThrow();
        return c;
    }

    private AdditionalAuthenticationResult result(AdditionalAuthenticationChallenge c) {
        String status = c.status(), code = c.failureCode();
        if ("WAITING_INPUT".equals(status) && c.generation() > 1) code = "AUTH_SMS_RETRY_REQUIRED";
        if (!c.expiresAt().isAfter(now())
                && Set.of("WAITING_INPUT", "SUBMITTED").contains(status)) {
            status = "EXPIRED";
            code = "AUTH_EXPIRED";
        }
        var mapped =
                code == null || code.startsWith("AUTH_")
                        ? null
                        : CertificateFailureStatus.from(code);
        String message = mapped == null ? null : mapped.getMessage();
        if ("CODEF_UNMAPPED_ERROR".equals(code)) message = "금융사에서 인증을 완료하지 못했어요. 잠시 후 다시 진행해 주세요.";
        if (code != null && message == null)
            message =
                    switch (code) {
                        case "AUTH_CANCELLED" -> "추가 인증이 취소되었습니다.";
                        case "AUTH_EXPIRED" -> "추가 인증 시간이 만료되었습니다.";
                        case "AUTH_SMS_RETRY_REQUIRED" -> "인증번호를 확인한 뒤 다시 입력해 주세요.";
                        default -> "추가 인증 결과를 확인해주세요.";
                    };
        return new AdditionalAuthenticationResult(
                c.id(),
                status,
                "SMS",
                c.maskedDestination(),
                c.expiresAt(),
                mapped == null ? code : mapped.getCode(),
                message,
                c.cancelRequested(),
                c.operationType(),
                c.organization(),
                c.status().equals("SUCCEEDED") && !c.certificate()
                        ? completion(c)
                                .map(FinanceAuthenticationCompletion::bankConnection)
                                .orElse(null)
                        : null,
                c.certificate()
                        ? pairings.findBySession(c.sessionId()).map(p -> p.id()).orElse(null)
                        : null,
                c.certificate() ? c.sessionId() : null,
                "FAILED".equals(c.status()) && "AUTH_SMS_RETRY_REQUIRED".equals(c.failureCode())
                        ? store.current(c.sessionId())
                                .filter(next -> next.generation() > c.generation())
                                .map(AdditionalAuthenticationChallenge::id)
                                .orElse(null)
                        : null);
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }
}
