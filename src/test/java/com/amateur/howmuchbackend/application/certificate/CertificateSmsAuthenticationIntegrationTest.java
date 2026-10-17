package com.amateur.howmuchbackend.application.certificate;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.amateur.howmuchbackend.adapter.out.persistence.certificate.*;
import com.amateur.howmuchbackend.adapter.out.security.*;
import com.amateur.howmuchbackend.application.auth.model.CurrentActor;
import com.amateur.howmuchbackend.application.certificate.model.*;
import com.amateur.howmuchbackend.application.certificate.policy.CertificateRegistrationPolicy;
import com.amateur.howmuchbackend.application.certificate.service.*;
import com.amateur.howmuchbackend.application.port.in.certificate.command.*;
import com.amateur.howmuchbackend.application.port.in.certificate.result.*;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.application.port.out.certificate.*;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.infra.certificate.config.CertificateProperties;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;

import software.amazon.awssdk.services.kms.KmsClient;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;

class CertificateSmsAuthenticationIntegrationTest {
    static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 10, 0, 0);
    static JdbcTemplate jdbc;
    static DataSourceTransactionManager manager;
    CertificateRegistrationJdbcAdapter storage;
    CertificateExecutionJdbcAdapter jobs;
    CertificateRelayJdbcAdapter relay;
    CertificateClaimJdbcAdapter claims;
    CertificateCapabilities caps;
    CertificateSecretProtectionAdapter protection;
    CertificateRegistrationService registration;
    CertificateExecutionTransactions tx;
    CertificateClaimService claimService;
    CurrentActorPort actor;
    CertificateProviderAccountPort provider;
    CertificateWorkerService worker;
    long user;
    final List<Long> extraUsers = new ArrayList<>();
    CertificateConsentJdbcAdapter consents;
    CertificatePairingJdbcAdapter pairings;
    CertificatePairingService pairingService;
    CertificateRegistrationTransactions registrationTx;
    CertificateApprovedRegistrationTransactions approved;
    CertificateConnectionDecisionService decisions;
    com.amateur.howmuchbackend.application.port.out.external.ExternalConnectionStorePort
            connections;
    com.amateur.howmuchbackend.application.port.out.external.BankConnectionPort banks;

    static GenericContainer<?> isolatedDb;
    MutableClock clock;
    com.amateur.howmuchbackend.adapter.out.persistence.external.AdditionalAuthenticationJdbcAdapter
            authStore;
    com.amateur.howmuchbackend.application.external.service.AdditionalAuthenticationService auth;
    com.amateur.howmuchbackend.application.external.service.AdditionalAuthenticationExecutionService
            authTx;
    com.amateur.howmuchbackend.application.external.service.AdditionalAuthenticationWorker
            authWorker;
    com.amateur.howmuchbackend.application.port.out.external.CodefAdditionalAuthenticationPort
            continuation;

    static class MutableClock extends Clock {
        Instant instant = NOW.toInstant(ZoneOffset.UTC);

        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        public Clock withZone(ZoneId z) {
            return this;
        }

        public Instant instant() {
            return instant;
        }

        void advance(long seconds) {
            instant = instant.plusSeconds(seconds);
        }
    }

    @BeforeAll
    static void database() {
        String url = System.getenv("HOWMUCH_SMS_TEST_JDBC_URL");
        if (url == null) {
            isolatedDb =
                    new GenericContainer<>(DockerImageName.parse("postgres:18.6"))
                            .withEnv("POSTGRES_HOST_AUTH_METHOD", "trust")
                            .withEnv("POSTGRES_DB", "sms_continuation_test")
                            .withExposedPorts(5432)
                            .waitingFor(
                                    Wait.forLogMessage(
                                            ".*database system is ready to accept connections.*\\n",
                                            2));
            isolatedDb.start();
            url =
                    "jdbc:postgresql://"
                            + isolatedDb.getHost()
                            + ":"
                            + isolatedDb.getMappedPort(5432)
                            + "/sms_continuation_test";
        } else if (!url.equals("jdbc:postgresql://127.0.0.1:28457/sms_continuation_test")) {
            throw new IllegalArgumentException(
                    "Only the dedicated loopback SMS test DB is allowed");
        }
        var ds = new DriverManagerDataSource(url, "postgres", "");
        Flyway.configure().dataSource(ds).load().migrate();
        jdbc = new JdbcTemplate(ds);
        manager = new DataSourceTransactionManager(ds);
    }

    @AfterAll
    static void stop() {
        if (isolatedDb != null) isolatedDb.stop();
    }

    @SuppressWarnings("unchecked")
    static <T> T transactional(T service) {
        var p = new ProxyFactory(service);
        p.setProxyTargetClass(true);
        p.addAdvice(
                new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        return (T) p.getProxy();
    }

    @BeforeEach
    void setup() {
        String unique = UUID.randomUUID().toString();
        user =
                jdbc.queryForObject(
                        "INSERT INTO"
                            + " users(identity_key,name,public_id,phone_number,pin_hash,phone_verified,role,active,created_at,updated_at)"
                            + " VALUES(?,'인증서 테스트',?,?,'unused',true,'USER',true,?,?) RETURNING id",
                        Long.class,
                        unique,
                        "c_" + unique.replace("-", "").substring(0, 10),
                        unique.substring(0, 20),
                        NOW,
                        NOW);
        clock = new MutableClock();
        var props =
                new CertificateProperties(
                        true,
                        "",
                        Base64.getEncoder().encodeToString(new byte[32]),
                        Base64.getEncoder().encodeToString(new byte[32]),
                        List.of(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null);
        caps = new CertificateCapabilities(props);
        protection =
                new CertificateSecretProtectionAdapter(
                        props, new StaticListableBeanFactory().getBeanProvider(KmsClient.class));
        storage = new CertificateRegistrationJdbcAdapter(jdbc);
        consents = new CertificateConsentJdbcAdapter(jdbc);
        pairings = new CertificatePairingJdbcAdapter(jdbc);
        jobs = new CertificateExecutionJdbcAdapter(jdbc);
        relay = new CertificateRelayJdbcAdapter(jdbc);
        claims = new CertificateClaimJdbcAdapter(jdbc);
        var policy = new CertificateRegistrationPolicy();
        registrationTx =
                transactional(
                        new CertificateRegistrationTransactions(
                                storage, storage, storage, caps, policy, clock, consents,
                                pairings));
        registration =
                new CertificateRegistrationService(
                        registrationTx,
                        protection,
                        caps,
                        () ->
                                List.of(
                                        new CertificateInstitutionResult(
                                                "0020", "테스트 은행", true, true, "synthetic-test", 3)),
                        key -> true,
                        storage,
                        policy,
                        clock);
        tx =
                transactional(
                        new CertificateExecutionTransactions(
                                jobs, relay, jobs, clock, storage, consents, pairings, policy));
        actor = mock(CurrentActorPort.class);
        when(actor.getCurrentActor()).thenReturn(new CurrentActor(user));
        var users = mock(com.amateur.howmuchbackend.application.port.out.user.LoadUserPort.class);
        var activeUser = mock(com.amateur.howmuchbackend.domain.user.User.class);
        when(activeUser.active()).thenReturn(true);
        when(users.findByIdForUpdate(user)).thenReturn(Optional.of(activeUser));
        claimService =
                transactional(
                        new CertificateClaimService(
                                storage, pairings, claims, caps, key -> true, actor, clock, users));
        provider = mock(CertificateProviderAccountPort.class);
        when(provider.register(anyString(), any(), anyBoolean()))
                .thenReturn(CertificateProviderResult.connected("connected-fixture"));
        decisions =
                transactional(
                        new CertificateConnectionDecisionService(
                                storage,
                                claims,
                                consents,
                                storage,
                                storage,
                                actor,
                                users,
                                clock,
                                pairings,
                                mock(CertificateApprovedRegistrationTransactions.class)));
        connections =
                mock(
                        com.amateur.howmuchbackend.application.port.out.external
                                .ExternalConnectionStorePort.class);
        banks =
                mock(
                        com.amateur.howmuchbackend.application.port.out.external.BankConnectionPort
                                .class);
        when(banks.registeredOrganizations(anyString())).thenReturn(Set.of("0020"));
        var cipher =
                new com.amateur.howmuchbackend.adapter.out.persistence.external.CodefSecretCipher(
                        new com.amateur.howmuchbackend.infra.codef.config.CodefProperties(
                                null,
                                null,
                                "",
                                "",
                                "",
                                Base64.getEncoder().encodeToString(new byte[32]),
                                null,
                                null));
        when(connections.findActiveByUserId(anyLong()))
                .thenAnswer(
                        invocation ->
                                jdbc
                                        .query(
                                                "SELECT id,user_id,connected_id_ciphertext FROM"
                                                    + " external_connections WHERE user_id=? AND"
                                                    + " active=true",
                                                (row, n) ->
                                                        new com.amateur.howmuchbackend.domain
                                                                .external.ExternalConnection(
                                                                row.getLong(1),
                                                                row.getLong(2),
                                                                cipher.decryptConnectedId(
                                                                        row.getLong(2),
                                                                        row.getString(3))),
                                                (Long) invocation.getArgument(0))
                                        .stream()
                                        .findFirst());
        when(connections.saveNew(any(), any()))
                .thenAnswer(
                        invocation -> {
                            com.amateur.howmuchbackend.domain.external.ExternalConnection c =
                                    invocation.getArgument(0);
                            com.amateur.howmuchbackend.domain.external.ConnectedInstitution i =
                                    invocation.getArgument(1);
                            Long id =
                                    jdbc.queryForObject(
                                            "INSERT INTO"
                                                + " external_connections(user_id,connected_id_ciphertext,configuration_fingerprint,active,created_at,updated_at)"
                                                + " VALUES(?,?,'test',true,?,?) RETURNING id",
                                            Long.class,
                                            c.userId(),
                                            cipher.encryptConnectedId(c.userId(), c.connectedId()),
                                            NOW,
                                            NOW);
                            jdbc.update(
                                    "INSERT INTO"
                                        + " connected_institutions(external_connection_id,organization,status,last_result_code,created_at,updated_at)"
                                        + " VALUES(?,?,'CONNECTED','CF-00000',?,?)",
                                    id,
                                    i.organization(),
                                    NOW,
                                    NOW);
                            return new com.amateur.howmuchbackend.domain.external
                                    .ExternalConnection(id, c.userId(), c.connectedId());
                        });
        when(connections.hasInstitution(anyLong(), anyString())).thenReturn(true);
        var credentialProtection =
                mock(
                        com.amateur.howmuchbackend.application.account.service
                                .AccountCredentialProtectionService.class);
        when(credentialProtection.protect(anyLong(), anyString(), any()))
                .thenReturn(
                        new com.amateur.howmuchbackend.application.account.model
                                .EncryptedBankAccountCredentials("test-encrypted-local", null));
        approved =
                transactional(
                        new CertificateApprovedRegistrationTransactions(
                                storage,
                                consents,
                                users,
                                connections,
                                banks,
                                provider,
                                credentialProtection,
                                protection,
                                jobs,
                                clock,
                                pairings));
        worker =
                new CertificateWorkerService(
                        tx,
                        storage,
                        protection,
                        provider,
                        p -> {},
                        clock,
                        () ->
                                List.of(
                                        new CertificateInstitutionResult(
                                                "0020", "테스트 은행", true, true, "synthetic-test", 3)),
                        approved,
                        pairings);
        decisions =
                transactional(
                        new CertificateConnectionDecisionService(
                                storage, claims, consents, storage, storage, actor, users, clock,
                                pairings, approved));
        pairingService =
                transactional(
                        new CertificatePairingService(
                                pairings,
                                () ->
                                        List.of(
                                                new CertificateInstitutionResult(
                                                        "0020", "우리은행", true, false, null, 3)),
                                caps,
                                key -> true,
                                actor,
                                users,
                                storage,
                                claims,
                                registration,
                                registrationTx,
                                clock));

        authStore =
                new com.amateur.howmuchbackend.adapter.out.persistence.external
                        .AdditionalAuthenticationJdbcAdapter(jdbc);
        continuation =
                mock(
                        com.amateur.howmuchbackend.application.port.out.external
                                .CodefAdditionalAuthenticationPort.class);
        when(continuation.acceptSms(any(), anyString())).thenAnswer(i -> i.getArgument(0));
        when(continuation.resume(any()))
                .thenReturn(CertificateProviderResult.connected("connected-fixture"));
        auth =
                transactional(
                        new com.amateur.howmuchbackend.application.external.service
                                .AdditionalAuthenticationService(
                                authStore,
                                protection,
                                continuation,
                                pairings,
                                storage,
                                registrationTx,
                                actor,
                                k -> true,
                                caps,
                                clock,
                                mock(
                                        com.amateur.howmuchbackend.application.notification.service
                                                .NotificationRecorder.class)));
        authTx =
                transactional(
                        new com.amateur.howmuchbackend.application.external.service
                                .AdditionalAuthenticationExecutionService(
                                authStore,
                                storage,
                                pairings,
                                jobs,
                                approved,
                                clock,
                                users,
                                mock(
                                        com.amateur.howmuchbackend.application.external.service
                                                .FinanceAuthenticationCompletionService.class),
                                connections,
                                protection,
                                continuation));
        authWorker =
                new com.amateur.howmuchbackend.application.external.service
                        .AdditionalAuthenticationWorker(
                        authTx, authStore, protection, continuation, storage, protection, clock);
        org.springframework.test.util.ReflectionTestUtils.setField(
                approved, "additionalAuthentication", auth);
        org.springframework.test.util.ReflectionTestUtils.setField(
                registrationTx, "additionalAuthentications", authStore);
        org.springframework.test.util.ReflectionTestUtils.setField(
                pairingService, "additionalAuthentication", auth);
        org.springframework.test.util.ReflectionTestUtils.setField(
                registration, "additionalAuthentication", auth);
    }

    @Test
    void genericBankContinuationPersistsEncryptedResultWithoutCertificateParent() {
        var operation =
                new com.amateur.howmuchbackend.application.external.model.FinanceOperation(
                        "BANK_CONNECT", "0020", null, null, null, null, false);
        byte[] bytes = "generic-private-context".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        when(continuation.prepareOperation(any(), any())).thenReturn(bytes.clone());
        var request =
                new com.amateur.howmuchbackend.application.external.model
                        .AdditionalAuthenticationRequest(
                        bytes, "010-****-0000", 170, NOW.plusSeconds(170));
        UUID id = auth.suspendOperation(user, operation, request);
        assertThat(auth.list())
                .extracting(
                        com.amateur.howmuchbackend.application.port.in.external.result
                                        .AdditionalAuthenticationResult
                                ::challengeId)
                .contains(id);
        assertThat(authStore.find(id, false).orElseThrow().execution()).isNull();
        assertThat(authStore.find(id, false).orElseThrow().operationType())
                .isEqualTo("BANK_CONNECT");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT convert_from(encrypted_payload,'LATIN1') FROM"
                                        + " codef_authentication_challenges WHERE id=?",
                                String.class,
                                id))
                .doesNotContain("generic-private-context");
        assertThatThrownBy(() -> auth.assertNoPending(user, "0020"))
                .isInstanceOf(
                        com.amateur.howmuchbackend.application.external.model
                                .AdditionalAuthenticationPendingException.class);
        var completion =
                mock(
                        com.amateur.howmuchbackend.application.external.service
                                .FinanceAuthenticationCompletionService.class);
        var safe =
                new com.amateur.howmuchbackend.application.external.model
                        .FinanceAuthenticationCompletion(operation, null, null);
        var providerResult =
                new com.amateur.howmuchbackend.application.external.model.FinanceOperationResult(
                        operation, null, null, null);
        when(continuation.resumeOperation(any())).thenReturn(providerResult);
        when(completion.complete(eq(user), any())).thenReturn(safe);
        when(continuation.encodeCompletion(any()))
                .thenReturn("private-result".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        when(continuation.decodeCompletion(any())).thenReturn(safe);
        org.springframework.test.util.ReflectionTestUtils.setField(
                authTx, "completion", completion);
        org.springframework.test.util.ReflectionTestUtils.setField(
                authTx, "connections", connections);
        org.springframework.test.util.ReflectionTestUtils.setField(authTx, "protector", protection);
        org.springframework.test.util.ReflectionTestUtils.setField(
                authTx, "provider", continuation);
        var submission = UUID.randomUUID();
        auth.submit(
                new com.amateur.howmuchbackend.application.port.in.external.command
                        .SubmitAdditionalAuthenticationSmsCommand(id, submission, "123456"));
        authWorker.executeOne();
        assertThat(auth.get(id).status()).isEqualTo("SUCCEEDED");
        assertThat(authStore.find(id, false).orElseThrow().payload()).isNull();
        assertThat(authStore.result(id, NOW)).isPresent();
        assertThat(
                        protection.openResult(
                                authStore.find(id, false).orElseThrow(),
                                authStore.result(id, NOW).orElseThrow()))
                .isEqualTo("private-result".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        auth.submit(
                new com.amateur.howmuchbackend.application.port.in.external.command
                        .SubmitAdditionalAuthenticationSmsCommand(id, submission, "000000"));
        authWorker.executeOne();
        verify(continuation, times(1)).resumeOperation(any());
        clock.advance(301);
        assertThat(authStore.result(id, LocalDateTime.now(clock))).isEmpty();
    }

    @Test
    void genericCancellationDoesNotRequireOrCancelCertificatePairing() {
        var operation =
                new com.amateur.howmuchbackend.application.external.model.FinanceOperation(
                        "BANK_CONNECT", "0020", null, null, null, null, false);
        when(continuation.prepareOperation(any(), any())).thenReturn(new byte[] {1});
        UUID id =
                auth.suspendOperation(
                        user,
                        operation,
                        new com.amateur.howmuchbackend.application.external.model
                                .AdditionalAuthenticationRequest(
                                new byte[] {1}, null, 170, NOW.plusSeconds(170)));
        when(actor.getCurrentActor()).thenReturn(new CurrentActor(user + 999));
        assertThatThrownBy(() -> auth.get(id))
                .isInstanceOf(ApplicationException.class)
                .extracting(e -> ((ApplicationException) e).getStatusCode().getStatus())
                .isEqualTo(404);
        when(actor.getCurrentActor()).thenReturn(new CurrentActor(user));
        assertThat(auth.cancel(id).status()).isEqualTo("CANCELLED");
        assertThat(authStore.find(id, false).orElseThrow().payload()).isNull();
        assertThat(auth.list()).isEmpty();
    }

    @Test
    void certificateChallengeCarriesOwnedPairingReferenceForNotificationResume() {
        UUID id = waiting();
        var result = auth.get(id);
        assertThat(result.pairingId()).isEqualTo(currentPair.id());
        assertThat(result.sessionId()).isEqualTo(currentSession.id());
    }

    @AfterEach
    void cleanup() {
        jdbc.execute(
                "TRUNCATE"
                    + " certificate_pc_pairings,certificate_provider_result_outbox,certificate_provider_connections,certificate_provider_command_inbox,certificate_upload_secrets,certificate_result_inbox,certificate_command_outbox,certificate_registration_claims,certificate_registration_sessions"
                    + " CASCADE");
        jdbc.update(
                "DELETE FROM connected_institutions WHERE external_connection_id IN (SELECT id FROM"
                        + " external_connections WHERE user_id=?)",
                user);
        jdbc.update("DELETE FROM external_connections WHERE user_id=?", user);
        jdbc.update("DELETE FROM users WHERE id=?", user);
        extraUsers.forEach(id -> jdbc.update("DELETE FROM users WHERE id=?", id));
    }

    CertificatePairingResult pairedDevice() {
        var p = pairingService.createPairing("127.0.0.1");
        assertThat(
                        pairingService
                                .pair(new PairCertificateDeviceCommand(p.id(), p.qrToken(), "0020"))
                                .status())
                .isEqualTo("PAIRED");
        return p;
    }

    CertificateRegistrationResult pairedUpload(CertificatePairingResult p) {
        var s =
                pairingService.createRegistration(
                        new StartPairedCertificateRegistrationCommand(
                                p.id(), p.pcCapability(), "0020", "127.0.0.1"));
        registration.submit(
                new SubmitCertificateUploadCommand(
                        s.id(),
                        s.pcCapability(),
                        s.uploadCapability(),
                        new byte[] {1},
                        new byte[] {2},
                        "password-fixture",
                        "123456",
                        "1234",
                        "19900101"));
        return s;
    }

    CertificatePairingResult currentPair;
    CertificateRegistrationResult currentSession;

    UUID waiting() {
        currentPair = pairedDevice();
        currentSession = pairedUpload(currentPair);
        var request =
                new com.amateur.howmuchbackend.application.external.model
                        .AdditionalAuthenticationRequest(
                        "synthetic-private-continuation"
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        "010-****-0000",
                        170,
                        null);
        when(provider.register(anyString(), any(), anyBoolean()))
                .thenThrow(
                        new com.amateur.howmuchbackend.application.external.model
                                .AdditionalAuthenticationRequiredException(request));
        var command = tx.claimPublication(false).orElseThrow();
        tx.published(command, true);
        tx.receiveCommand(command.command(), caps.hash("sms-fixture"));
        worker.executeOne();
        assertThat(registration.get(currentSession.id(), currentSession.pcCapability()).status())
                .isEqualTo("REGISTERING");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM certificate_provider_command_inbox WHERE"
                                        + " command_type='REGISTER'",
                                String.class))
                .isEqualTo("WAITING_AUTH");
        return authStore.current(currentSession.id()).orElseThrow().id();
    }

    void submit(UUID id, UUID submission) {
        auth.submit(
                new com.amateur.howmuchbackend.application.port.in.external.command
                        .SubmitAdditionalAuthenticationSmsCommand(id, submission, "123456"));
    }

    void deliverResult() {
        var result = tx.claimPublication(true).orElseThrow();
        tx.receiveResult(result.event(), caps.hash("sms-result"));
        tx.published(result, true);
    }

    @Test
    void successResumesExactlyOnceAndStillRequiresFinalYes() {
        var id = waiting();
        assertThat(
                        pairingService
                                .getMobilePairing(currentPair.id())
                                .additionalAuthentication()
                                .status())
                .isEqualTo("WAITING_INPUT");
        UUID submission = UUID.randomUUID();
        submit(id, submission);
        submit(id, submission);
        authWorker.executeOne();
        authWorker.executeOne();
        assertThat(auth.get(id).status()).isEqualTo("SUCCEEDED");
        assertThat(connections.findActiveByUserId(user)).isEmpty();
        assertThat(consents.find(currentSession.id())).isEmpty();
        assertThat(authStore.find(id, false).orElseThrow().payload()).isNull();
        deliverResult();
        assertThat(pairingService.getMobilePairing(currentPair.id()).status())
                .isEqualTo("READY_FOR_CLAIM");
        var claim = pairingService.requestApproval(currentPair.id());
        assertThat(
                        decisions
                                .decide(
                                        new DecideCertificateConnectionCommand(
                                                currentSession.id(), claim.claimId(), true))
                                .status())
                .isEqualTo("BOUND");
        verify(provider, times(1)).register(anyString(), any(), anyBoolean());
        verify(continuation, times(1)).resume(any());
    }

    @Test
    void retryUsesNewGenerationWithoutMutatingAcceptedSubmissionOrApprovingConnection() {
        var id = waiting();
        var submission = UUID.randomUUID();
        var retry =
                new com.amateur.howmuchbackend.application.external.model
                        .AdditionalAuthenticationRequest(
                        "retry-private-context".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        "010-****-0000",
                        170,
                        NOW.plusSeconds(120));
        when(continuation.resume(any()))
                .thenThrow(
                        new com.amateur.howmuchbackend.application.external.model
                                .AdditionalAuthenticationRequiredException(retry))
                .thenReturn(CertificateProviderResult.connected("connected-fixture"));
        submit(id, submission);
        authWorker.executeOne();
        var failed = auth.get(id);
        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(failed.retryChallengeId()).isNotNull().isNotEqualTo(id);
        var next = authStore.find(failed.retryChallengeId(), false).orElseThrow();
        assertThat(next.status()).isEqualTo("WAITING_INPUT");
        assertThat(next.generation()).isEqualTo(2);
        assertThat(next.expiresAt()).isEqualTo(NOW.plusSeconds(120));
        assertThat(auth.get(next.id()).failureCode()).isEqualTo("AUTH_SMS_RETRY_REQUIRED");
        assertThat(authStore.find(id, false).orElseThrow().submissionId()).isEqualTo(submission);
        assertThat(authStore.find(id, false).orElseThrow().payload()).isNull();
        authWorker.executeOne();
        verify(continuation, times(1)).resume(any());
        submit(id, submission);
        verify(continuation, times(1)).resume(any());
        submit(next.id(), UUID.randomUUID());
        authWorker.executeOne();
        assertThat(auth.get(next.id()).status()).isEqualTo("SUCCEEDED");
        assertThat(connections.findActiveByUserId(user)).isEmpty();
        deliverResult();
        assertThat(pairingService.getMobilePairing(currentPair.id()).status())
                .isEqualTo("READY_FOR_CLAIM");
        verify(provider, times(1)).register(anyString(), any(), anyBoolean());
        verify(continuation, times(2)).resume(any());
    }

    @Test
    void cancellingRetryDoesNotCallProviderAgain() {
        var id = waiting();
        var retry =
                new com.amateur.howmuchbackend.application.external.model
                        .AdditionalAuthenticationRequest(new byte[] {1}, null, 170, null);
        when(continuation.resume(any()))
                .thenThrow(
                        new com.amateur.howmuchbackend.application.external.model
                                .AdditionalAuthenticationRequiredException(retry));
        submit(id, UUID.randomUUID());
        authWorker.executeOne();
        var next = auth.get(id).retryChallengeId();
        auth.cancel(id);
        authWorker.executeOne();
        assertThat(auth.get(next).status()).isEqualTo("CANCELLED");
        verify(continuation, times(1)).resume(any());
    }

    @Test
    void duplicateSubmissionCannotReplaceAcceptedSms() {
        var id = waiting();
        UUID submission = UUID.randomUUID();
        submit(id, submission);
        assertThatThrownBy(() -> submit(id, UUID.randomUUID()))
                .isInstanceOf(ApplicationException.class)
                .extracting(e -> ((ApplicationException) e).getStatusCode().getCode())
                .isEqualTo("FINANCE_AUTHENTICATION_CONFLICT");
        assertThat(authStore.find(id, false).orElseThrow().submissionId()).isEqualTo(submission);
    }

    @Test
    void anotherUserCannotInspectSubmitOrCancel() {
        var id = waiting();
        when(actor.getCurrentActor()).thenReturn(new CurrentActor(user + 1));
        assertThatThrownBy(() -> auth.get(id))
                .isInstanceOf(ApplicationException.class)
                .extracting(e -> ((ApplicationException) e).getStatusCode().getStatus())
                .isEqualTo(404);
        assertThatThrownBy(() -> submit(id, UUID.randomUUID()))
                .isInstanceOf(ApplicationException.class);
        assertThatThrownBy(() -> auth.cancel(id)).isInstanceOf(ApplicationException.class);
        assertThat(authStore.find(id, false).orElseThrow().status()).isEqualTo("WAITING_INPUT");
    }

    @Test
    void cancellationBeforeSendingErasesContextAndNeverCallsProvider() {
        var id = waiting();
        submit(id, UUID.randomUUID());
        assertThat(auth.cancel(id).status()).isEqualTo("CANCELLED");
        authWorker.executeOne();
        authWorker.recover();
        verify(continuation, never()).resume(any());
        assertThat(authStore.find(id, false).orElseThrow().payload()).isNull();
        assertThat(
                        pairingService
                                .getPcPairing(currentPair.id(), currentPair.pcCapability())
                                .status())
                .isEqualTo("CANCELLED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM certificate_provider_command_inbox WHERE"
                                        + " command_type='REGISTER'",
                                String.class))
                .isEqualTo("FAILED");
    }

    @Test
    void expiryRejectsSubmissionAndErasesContextWithoutRegisterRetry() {
        var id = waiting();
        clock.advance(171);
        assertThatThrownBy(() -> submit(id, UUID.randomUUID()))
                .isInstanceOf(ApplicationException.class)
                .extracting(e -> ((ApplicationException) e).getStatusCode().getStatus())
                .isEqualTo(410);
        authWorker.recover();
        authWorker.executeOne();
        worker.executeOne();
        assertThat(auth.get(id).status()).isEqualTo("EXPIRED");
        assertThat(authStore.find(id, false).orElseThrow().payload()).isNull();
        verify(provider, times(1)).register(anyString(), any(), anyBoolean());
        verify(continuation, never()).resume(any());
    }

    @Test
    void leaseLossBeforeHttpMayResumeFromSameSubmission() {
        var id = waiting();
        UUID submission = UUID.randomUUID();
        submit(id, submission);
        var old = authTx.claim().orElseThrow();
        clock.advance(61);
        authWorker.recover();
        assertThat(authStore.find(id, false).orElseThrow().status()).isEqualTo("SUBMITTED");
        assertThat(authTx.start(old)).isFalse();
        authWorker.executeOne();
        assertThat(auth.get(id).status()).isEqualTo("SUCCEEDED");
        verify(continuation, times(1)).resume(any());
    }

    @Test
    void leaseLossAfterHttpIsUnknownAndNeverReplayed() {
        var id = waiting();
        submit(id, UUID.randomUUID());
        var c = authTx.claim().orElseThrow();
        assertThat(authTx.start(c)).isTrue();
        authTx.recover(id);
        assertThat(auth.get(id).status()).isEqualTo("PROCESSING");
        clock.advance(61);
        authWorker.recover();
        authWorker.executeOne();
        worker.executeOne();
        assertThat(auth.get(id).status()).isEqualTo("UNKNOWN");
        assertThat(authStore.find(id, false).orElseThrow().payload()).isNull();
        assertThat(pairingService.getMobilePairing(currentPair.id()).status()).isEqualTo("UNKNOWN");
        verify(continuation, never()).resume(any());
        verify(provider, times(1)).register(anyString(), any(), anyBoolean());
    }

    @Test
    void responseLossIsUnknownAndDoesNotRepeatContinuation() {
        var id = waiting();
        submit(id, UUID.randomUUID());
        when(continuation.resume(any()))
                .thenThrow(new IllegalStateException("synthetic transport timeout"));
        authWorker.executeOne();
        authWorker.executeOne();
        authWorker.recover();
        assertThat(auth.get(id).status()).isEqualTo("UNKNOWN");
        verify(continuation, times(1)).resume(any());
    }

    @Test
    void lateSuccessAfterMobileCancellationCannotSaveConnection() {
        var id = waiting();
        submit(id, UUID.randomUUID());
        when(continuation.resume(any()))
                .thenAnswer(
                        i -> {
                            pairingService.cancelMobilePairing(currentPair.id());
                            return CertificateProviderResult.connected("late-synthetic-connection");
                        });
        authWorker.executeOne();
        assertThat(auth.get(id).status()).isEqualTo("CANCELLED");
        assertThat(connections.findActiveByUserId(user)).isEmpty();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM certificate_provider_connections",
                                Integer.class))
                .isZero();
    }

    @Test
    void providerRejectionUsesCommonFailureMessageAndAllowsPcRetry() {
        var id = waiting();
        submit(id, UUID.randomUUID());
        when(continuation.resume(any())).thenReturn(CertificateProviderResult.rejected("CF-12800"));
        authWorker.executeOne();
        deliverResult();
        assertThat(auth.get(id).status()).isEqualTo("FAILED");
        assertThat(auth.get(id).failureMessage()).isNotBlank();
        assertThat(pairingService.getMobilePairing(currentPair.id()).status()).isEqualTo("FAILED");
        assertThat(authStore.find(id, false).orElseThrow().payload()).isNull();
        assertThat(
                        pairingService
                                .createRegistration(
                                        new StartPairedCertificateRegistrationCommand(
                                                currentPair.id(),
                                                currentPair.pcCapability(),
                                                "0020",
                                                "127.0.0.1"))
                                .id())
                .isNotEqualTo(currentSession.id());
    }

    @Test
    void encryptionBindsOwnerOperationAndGeneration() {
        var id = waiting();
        var c = authStore.find(id, false).orElseThrow();
        var tampered =
                new com.amateur.howmuchbackend.application.external.model
                        .AdditionalAuthenticationChallenge(
                        c.id(),
                        c.userId() + 1,
                        c.sessionId(),
                        c.organization(),
                        c.generation(),
                        c.status(),
                        c.maskedDestination(),
                        c.failureCode(),
                        c.submissionId(),
                        c.leaseToken(),
                        c.leaseGeneration(),
                        c.requestStarted(),
                        c.cancelRequested(),
                        c.expiresAt(),
                        c.leaseExpiresAt(),
                        c.payload(),
                        c.execution());
        assertThatThrownBy(() -> protection.openAuthentication(tampered))
                .isInstanceOf(ApplicationException.class);
    }

    @Test
    void mobileHttpContractReturnsAcceptedAndNeverExposesStoredSecrets() throws Exception {
        var id = waiting();
        var http =
                org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
                                new com.amateur.howmuchbackend.adapter.in.web.external
                                        .AdditionalAuthenticationController(auth, auth, auth, auth))
                        .setControllerAdvice(
                                new com.amateur.howmuchbackend.common.exception
                                        .GlobalExceptionHandler())
                        .build();
        String path = "/api/v2/finance-authentications/" + id;
        var before =
                http.perform(
                                org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                        .get(path))
                        .andExpect(
                                org.springframework.test.web.servlet.result.MockMvcResultMatchers
                                        .status()
                                        .isOk())
                        .andExpect(
                                org.springframework.test.web.servlet.result.MockMvcResultMatchers
                                        .jsonPath("$.data.status")
                                        .value("WAITING_INPUT"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertThat(before)
                .doesNotContain("payload", "wrapped", "synthetic-private", "password", "twoWay");
        UUID submission = UUID.randomUUID();
        String body = "{\"submissionId\":\"" + submission + "\",\"code\":\"012345\"}";
        http.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                                        path + "/sms")
                                .contentType("application/json")
                                .content(body))
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                                .isAccepted())
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                                        "$.data.status")
                                .value("SUBMITTED"));
        http.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                                        path + "/sms")
                                .contentType("application/json")
                                .content(
                                        body.replace(
                                                submission.toString(),
                                                UUID.randomUUID().toString())))
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                                .isConflict());
        http.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                                        path + "/sms")
                                .contentType("application/json")
                                .content(
                                        "{\"submissionId\":\""
                                                + submission
                                                + "\",\"code\":\"bad\"}"))
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                                .isBadRequest());
        when(actor.getCurrentActor()).thenReturn(new CurrentActor(user + 1));
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path))
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                                .isNotFound());
    }

    @Test
    void concurrentWorkersSendOneContinuation() throws Exception {
        var id = waiting();
        submit(id, UUID.randomUUID());
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> authWorker.executeOne());
            var second = executor.submit(() -> authWorker.executeOne());
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        }
        assertThat(auth.get(id).status()).isEqualTo("SUCCEEDED");
        verify(continuation, times(1)).resume(any());
    }

    @Test
    void anotherPairingCannotStartRegistrationWhileSmsIsWaiting() {
        UUID first = waiting();
        var second = pairedDevice();
        var session = pairedUpload(second);
        var command = tx.claimPublication(false).orElseThrow();
        tx.published(command, true);
        tx.receiveCommand(command.command(), caps.hash("second-sms-command"));
        worker.executeOne();
        assertThat(auth.get(first).status()).isEqualTo("WAITING_INPUT");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT last_failure_code FROM certificate_provider_command_inbox"
                                        + " WHERE session_id=?",
                                String.class,
                                session.id()))
                .isEqualTo("CONTRACT_CONFLICT");
        verify(provider, times(1)).register(anyString(), any(), anyBoolean());
    }

    @Test
    void deactivatedUserCannotSendContinuation() {
        var id = waiting();
        submit(id, UUID.randomUUID());
        var users =
                (com.amateur.howmuchbackend.application.port.out.user.LoadUserPort)
                        org.springframework.test.util.ReflectionTestUtils.getField(authTx, "users");
        when(users.findByIdForUpdate(user)).thenReturn(Optional.empty());
        authWorker.executeOne();
        assertThat(auth.get(id).status()).isEqualTo("CANCELLED");
        verify(continuation, never()).resume(any());
    }
}
