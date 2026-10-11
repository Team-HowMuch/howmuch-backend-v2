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
import com.amateur.howmuchbackend.application.port.in.certificate.command.ReceiveCertificateCommand;
import com.amateur.howmuchbackend.application.port.in.certificate.result.*;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.application.port.out.certificate.*;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.infra.certificate.config.CertificateProperties;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;

import software.amazon.awssdk.services.kms.KmsClient;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;

@Testcontainers
class CertificatePersistenceIntegrationTest {
    @Container
    static final GenericContainer<?> DB =
            new GenericContainer<>(DockerImageName.parse("postgres:16.14"))
                    .withEnv("POSTGRES_HOST_AUTH_METHOD", "trust")
                    .withEnv("POSTGRES_DB", "certificate_test")
                    .withExposedPorts(5432)
                    .waitingFor(
                            Wait.forLogMessage(
                                    ".*database system is ready to accept connections.*\\n", 2));

    @Container
    static final org.testcontainers.kafka.KafkaContainer BROKER =
            new org.testcontainers.kafka.KafkaContainer("apache/kafka:4.2.2")
                    .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false");

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
    CertificateConsentJdbcAdapter consents;
    CertificateConnectionDecisionService decisions;
    com.amateur.howmuchbackend.application.port.out.external.ExternalConnectionStorePort
            connections;
    com.amateur.howmuchbackend.application.port.out.external.BankConnectionPort banks;

    @BeforeAll
    static void database() {
        var ds =
                new DriverManagerDataSource(
                        "jdbc:postgresql://"
                                + DB.getHost()
                                + ":"
                                + DB.getMappedPort(5432)
                                + "/certificate_test",
                        "postgres",
                        "");
        Flyway.configure().dataSource(ds).load().migrate();
        jdbc = new JdbcTemplate(ds);
        manager = new DataSourceTransactionManager(ds);
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
        var clock = Clock.fixed(NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
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
        var env = new MockEnvironment();
        env.setActiveProfiles("test");
        caps = new CertificateCapabilities(props);
        protection =
                new CertificateSecretProtectionAdapter(
                        props,
                        new StaticListableBeanFactory().getBeanProvider(KmsClient.class),
                        env);
        storage = new CertificateRegistrationJdbcAdapter(jdbc);
        consents = new CertificateConsentJdbcAdapter(jdbc);
        jobs = new CertificateExecutionJdbcAdapter(jdbc);
        relay = new CertificateRelayJdbcAdapter(jdbc);
        claims = new CertificateClaimJdbcAdapter(jdbc);
        var policy = new CertificateRegistrationPolicy();
        var registrationTx =
                transactional(
                        new CertificateRegistrationTransactions(
                                storage, storage, storage, caps, policy, clock, consents));
        registration =
                new CertificateRegistrationService(
                        registrationTx,
                        protection,
                        caps,
                        () ->
                                List.of(
                                        new CertificateInstitutionResult(
                                                "0020",
                                                "테스트 은행",
                                                true,
                                                true,
                                                true,
                                                true,
                                                "synthetic-test")),
                        key -> true,
                        storage,
                        policy,
                        clock);
        tx =
                transactional(
                        new CertificateExecutionTransactions(
                                jobs, relay, jobs, clock, storage, consents, policy));
        actor = mock(CurrentActorPort.class);
        when(actor.getCurrentActor()).thenReturn(new CurrentActor(user));
        var users = mock(com.amateur.howmuchbackend.application.port.out.user.LoadUserPort.class);
        var activeUser = mock(com.amateur.howmuchbackend.domain.user.User.class);
        when(activeUser.active()).thenReturn(true);
        when(users.findByIdForUpdate(user)).thenReturn(Optional.of(activeUser));
        claimService =
                transactional(
                        new CertificateClaimService(
                                storage, claims, caps, key -> true, actor, clock, users));
        provider = mock(CertificateProviderAccountPort.class);
        when(provider.register(anyString(), any(), anyBoolean()))
                .thenReturn(CertificateProviderResult.connected("connected-fixture"));
        decisions =
                transactional(
                        new CertificateConnectionDecisionService(
                                storage, claims, consents, storage, storage, actor, users, clock));
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
        var approved =
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
                                clock));
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
                                                "0020",
                                                "테스트 은행",
                                                true,
                                                true,
                                                true,
                                                true,
                                                "synthetic-test")),
                        approved);
    }

    @AfterEach
    void cleanup() {
        jdbc.execute(
                "TRUNCATE"
                    + " certificate_provider_result_outbox,certificate_provider_connections,certificate_provider_command_inbox,certificate_upload_secrets,certificate_result_inbox,certificate_command_outbox,certificate_registration_claims,certificate_registration_sessions"
                    + " CASCADE");
        jdbc.update(
                "DELETE FROM connected_institutions WHERE external_connection_id IN (SELECT id FROM"
                    + " external_connections WHERE user_id=?)",
                user);
        jdbc.update("DELETE FROM external_connections WHERE user_id=?", user);
        jdbc.update("DELETE FROM users WHERE id=?", user);
    }

    String lastPc;

    CertificateRegistrationResult submitted() {
        var s = registration.create(new CreateCertificateRegistrationCommand("0020", "127.0.0.1"));
        lastPc = s.pcCapability();
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

    void approveReady() {
        var pending =
                jdbc.query(
                        "SELECT id FROM certificate_registration_sessions WHERE"
                            + " status='READY_FOR_CLAIM'",
                        (row, n) -> row.getObject(1, UUID.class));
        for (UUID id : pending) {
            var qr = claimService.issue(id, lastPc);
            var claim =
                    claimService.reserve(
                            new ReserveCertificateClaimCommand(
                                    id, qr.qrToken(), UUID.randomUUID()));
            decisions.decide(new DecideCertificateConnectionCommand(id, claim.claimId(), true));
        }
    }

    ReceiveCertificateCommand deliverCommand() {
        approveReady();
        var p = tx.claimPublication(false).orElseThrow();
        tx.published(p, true);
        assertThat(tx.receiveCommand(p.command(), caps.hash("payload"))).isTrue();
        return p.command();
    }

    void successfulRegistration() {
        when(provider.register(anyString(), any(), anyBoolean()))
                .thenReturn(CertificateProviderResult.connected("connected-fixture"));
        worker.executeOne();
        var p = tx.claimPublication(true).orElseThrow();
        assertThat(tx.receiveResult(p.event(), caps.hash("result"))).isTrue();
        tx.published(p, true);
    }

    @Test
    void uploadCapabilityIsSingleUseAndCreatesOnlyOneOutbox() {
        var s = submitted();
        assertThatThrownBy(
                        () ->
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
                                                "19900101")))
                .isInstanceOf(ApplicationException.class);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM certificate_command_outbox", Integer.class))
                .isZero(); // Upload alone must never dispatch a provider registration.
        assertThat(
                        new String(
                                jdbc.queryForObject(
                                        "SELECT encrypted_payload FROM certificate_upload_secrets",
                                        byte[].class),
                                java.nio.charset.StandardCharsets.ISO_8859_1))
                .doesNotContain("password-fixture");
        assertThatThrownBy(() -> registration.get(s.id(), caps.mint()))
                .isInstanceOf(ApplicationException.class);
    }

    @Test
    void fullRegistrationProducesQrAndIdempotentExclusiveClaim() {
        var s = submitted();
        assertThat(registration.get(s.id(), s.pcCapability()).status())
                .isEqualTo("READY_FOR_CLAIM");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT encrypted_payload IS NULL FROM certificate_upload_secrets",
                                Boolean.class))
                .isFalse();
        verifyNoInteractions(provider);
        var qr = claimService.issue(s.id(), s.pcCapability());
        UUID key = UUID.randomUUID();
        var command = new ReserveCertificateClaimCommand(s.id(), qr.qrToken(), key);
        var c = claimService.reserve(command);
        assertThat(claimService.reserve(command)).isEqualTo(c);
        assertThat(claimService.inspect(s.id(), s.pcCapability()).confirmationCode())
                .isEqualTo(c.confirmationCode());
        assertThatThrownBy(
                        () ->
                                claimService.reserve(
                                        new ReserveCertificateClaimCommand(
                                                s.id(), qr.qrToken(), UUID.randomUUID())))
                .isInstanceOf(ApplicationException.class);
        claimService.reject(s.id(), s.pcCapability(), c.claimId());
        var next = claimService.issue(s.id(), s.pcCapability());
        assertThat(next.generation()).isGreaterThan(qr.generation());
        assertThatThrownBy(
                        () ->
                                claimService.reserve(
                                        new ReserveCertificateClaimCommand(
                                                s.id(), qr.qrToken(), UUID.randomUUID())))
                .isInstanceOf(ApplicationException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM external_connections", Integer.class))
                .isZero();
    }

    @Test
    void duplicateCommandIsAcceptedButConflictingPayloadIsRejected() {
        submitted();
        var c = deliverCommand();
        assertThat(tx.receiveCommand(c, caps.hash("payload"))).isTrue();
        assertThat(tx.receiveCommand(c, caps.hash("conflict"))).isFalse();
        worker.executeOne();
        verify(provider, times(1)).register(anyString(), any(), anyBoolean());
        worker.executeOne();
        verifyNoMoreInteractions(provider);
    }

    @Test
    void timeoutIsUnknownAndCannotBeAutomaticallyRetried() {
        var s = submitted();
        deliverCommand();
        when(provider.register(anyString(), any(), anyBoolean()))
                .thenThrow(new RuntimeException("synthetic-timeout"));
        worker.executeOne();
        worker.recover();
        worker.executeOne();
        verify(provider, times(1)).register(anyString(), any(), anyBoolean());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM certificate_provider_command_inbox",
                                String.class))
                .isEqualTo("UNKNOWN");
        var p = tx.claimPublication(true).orElseThrow();
        tx.receiveResult(p.event(), caps.hash("unknown"));
        assertThat(registration.get(s.id(), s.pcCapability()).status()).isEqualTo("UNKNOWN");
    }

    @Test
    void twoWorkersCannotClaimOneExecution() throws Exception {
        submitted();
        deliverCommand();
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var gate = new CountDownLatch(1);
            var tasks = new ArrayList<Future<Boolean>>();
            for (int i = 0; i < 8; i++)
                tasks.add(
                        pool.submit(
                                () -> {
                                    gate.await();
                                    return tx.claim().isPresent();
                                }));
            gate.countDown();
            int count = 0;
            for (var task : tasks) if (task.get(10, TimeUnit.SECONDS)) count++;
            assertThat(count).isEqualTo(1);
        }
    }

    @Test
    void expiredPublisherLeaseCannotCompleteNewAttempt() {
        submitted();
        approveReady();
        var old = tx.claimPublication(false).orElseThrow();
        new TransactionTemplate(manager)
                .executeWithoutResult(t -> relay.recoverPublications(NOW.plusSeconds(31)));
        var next =
                new TransactionTemplate(manager)
                        .execute(
                                t ->
                                        relay.claimPublication(false, NOW.plusSeconds(32))
                                                .orElseThrow());
        assertThat(next.generation()).isGreaterThan(old.generation());
        assertThat(
                        (Boolean)
                                new TransactionTemplate(manager)
                                        .execute(
                                                t ->
                                                        relay.completePublication(
                                                                old, true, NOW.plusSeconds(33))))
                .isFalse();
        assertThat(
                        (Boolean)
                                new TransactionTemplate(manager)
                                        .execute(
                                                t ->
                                                        relay.completePublication(
                                                                next, true, NOW.plusSeconds(33))))
                .isTrue();
    }

    @Test
    void crashBeforeRequestCanRetryButCrashAfterRequestCannot() {
        submitted();
        deliverCommand();
        var e = tx.claim().orElseThrow();
        new TransactionTemplate(manager)
                .executeWithoutResult(t -> jobs.recover(NOW.plusMinutes(11)));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM certificate_provider_command_inbox",
                                String.class))
                .isEqualTo("RETRY");
        assertThat(
                        (Boolean)
                                new TransactionTemplate(manager)
                                        .execute(t -> jobs.startRequest(e, NOW.plusMinutes(11))))
                .isFalse();
    }

    @Test
    void lateConfirmedSuccessReconcilesUnknownWithoutRepeatingCreate() {
        submitted();
        var c = deliverCommand();
        var e = tx.claim().orElseThrow();
        assertThat(tx.start(e)).isTrue();
        new TransactionTemplate(manager)
                .executeWithoutResult(t -> jobs.recover(NOW.plusMinutes(11)));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM certificate_provider_command_inbox",
                                String.class))
                .isEqualTo("UNKNOWN");
        UUID ref = UUID.randomUUID();
        var secret =
                protection.sealConnection(
                        c.sessionId(),
                        ref,
                        new CertificateConnectionSecret("connected-late", null, null, null));
        new TransactionTemplate(manager)
                .executeWithoutResult(
                        t -> jobs.finish(e, "SUCCEEDED", null, ref, secret, NOW.plusMinutes(12)));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM certificate_provider_command_inbox",
                                String.class))
                .isEqualTo("SUCCEEDED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM certificate_provider_result_outbox",
                                Integer.class))
                .isEqualTo(2);
        verifyNoInteractions(provider);
    }

    @Test
    void cancellationBeforeExecutionDoesNotContactProvider() {
        var s = submitted();
        registration.cancel(s.id(), s.pcCapability());
        var cancel = tx.claimPublication(false).orElseThrow();
        tx.receiveCommand(cancel.command(), caps.hash("cancel-before-consent"));
        worker.executeOne();
        verifyNoInteractions(provider);
        assertThat(registration.get(s.id(), s.pcCapability()).status()).isEqualTo("CANCELLED");
    }

    @ParameterizedTest
    @CsvSource({
        "SUCCESS,BOUND,",
        "CF-12302,FAILED,BANK_CERTIFICATE_PASSWORD_INVALID",
        "CF-99998,FAILED,CODEF_UNMAPPED_ERROR"
    })
    void actualKafkaRoundTripCommitsBothInboxesBeforeAcknowledgement(
            String providerCode, String expectedStatus, String commonCode) {
        if (!providerCode.equals("SUCCESS"))
            when(provider.register(anyString(), any(), anyBoolean()))
                    .thenReturn(CertificateProviderResult.rejected(providerCode));
        var s = submitted();
        approveReady();
        var json = new tools.jackson.databind.ObjectMapper();
        var props =
                new CertificateProperties(
                        true,
                        "",
                        "",
                        "",
                        List.of(),
                        "command." + UUID.randomUUID(),
                        "result." + UUID.randomUUID(),
                        null,
                        null,
                        null,
                        null);
        try (var admin =
                org.apache.kafka.clients.admin.AdminClient.create(
                        Map.of("bootstrap.servers", BROKER.getBootstrapServers()))) {
            try {
                admin.createTopics(
                                List.of(
                                        new org.apache.kafka.clients.admin.NewTopic(
                                                props.commandTopic(), 1, (short) 1),
                                        new org.apache.kafka.clients.admin.NewTopic(
                                                props.resultTopic(), 1, (short) 1)))
                        .all()
                        .get(10, TimeUnit.SECONDS);
            } catch (Exception failure) {
                throw new RuntimeException(failure);
            }
            var producer =
                    new org.springframework.kafka.core.DefaultKafkaProducerFactory<String, String>(
                            Map.of(
                                    "bootstrap.servers",
                                    BROKER.getBootstrapServers(),
                                    "key.serializer",
                                    org.apache.kafka.common.serialization.StringSerializer.class,
                                    "value.serializer",
                                    org.apache.kafka.common.serialization.StringSerializer.class,
                                    "acks",
                                    "all",
                                    "enable.idempotence",
                                    true));
            var template =
                    new org.springframework.kafka.core.KafkaTemplate<String, String>(producer);
            var publisher =
                    new com.amateur.howmuchbackend.adapter.out.kafka.certificate
                            .CertificateKafkaPublisher(
                            new com.amateur.howmuchbackend.infra.messaging.kafka
                                    .AcknowledgedKafkaSender(template, Duration.ofSeconds(5)),
                            json,
                            props);
            var listener =
                    new com.amateur.howmuchbackend.adapter.in.kafka.certificate
                            .CertificateKafkaListener(json, tx);
            try {
                var command = tx.claimPublication(false).orElseThrow();
                publisher.publish(command);
                tx.published(command, true);
                var record = consume(props.commandTopic());
                listener.command(record);
                listener.command(record);
                worker.executeOne();
                worker.executeOne();
                verify(provider, times(1)).register(anyString(), any(), anyBoolean());
                var event = tx.claimPublication(true).orElseThrow();
                publisher.publish(event);
                tx.published(event, true);
                var result = consume(props.resultTopic());
                listener.result(result);
                listener.result(result);
                var state = registration.get(s.id(), s.pcCapability());
                assertThat(state.status()).isEqualTo(expectedStatus);
                assertThat(state.failureCode()).isEqualTo(commonCode);
                if (commonCode != null) {
                    assertThat(state.failureMessage()).isNotBlank().doesNotContain(providerCode);
                    assertThat(
                                    jdbc.queryForObject(
                                            "SELECT last_failure_code FROM"
                                                + " certificate_provider_command_inbox",
                                            String.class))
                            .isEqualTo(providerCode);
                    assertThat(
                                    jdbc.queryForObject(
                                            "SELECT event_failure_code FROM"
                                                + " certificate_provider_result_outbox",
                                            String.class))
                            .isEqualTo(providerCode);
                    assertThat(
                                    jdbc.queryForObject(
                                            "SELECT last_failure_code FROM"
                                                + " certificate_registration_sessions WHERE id=?",
                                            String.class,
                                            s.id()))
                            .isEqualTo(providerCode);
                    assertThat(result.value())
                            .contains(providerCode)
                            .doesNotContain("failureMessage", "message\":");
                    assertThatThrownBy(
                                    () ->
                                            jdbc.update(
                                                    "UPDATE certificate_provider_result_outbox SET"
                                                        + " event_failure_code='CF-13031'"))
                            .isInstanceOf(
                                    org.springframework.dao.DataIntegrityViolationException.class);
                }
                assertThat(record.value())
                        .doesNotContain("password", "derFile", "keyFile", "accountNumber");
                assertThat(result.value()).doesNotContain("connected-fixture");
            } finally {
                template.destroy();
                producer.destroy();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"CF-00000", "CF-12302\n", "raw-secret", "CF-1232"})
    void unsafeCodesRollBackJobAndResultTogether(String code) {
        submitted();
        deliverCommand();
        var execution = tx.claim().orElseThrow();
        assertThat(tx.start(execution)).isTrue();
        assertThatThrownBy(() -> tx.finish(execution, "FAILED", code, null, null))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM certificate_provider_command_inbox",
                                String.class))
                .isEqualTo("PROCESSING");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM certificate_provider_result_outbox",
                                Integer.class))
                .isZero();
        assertThatThrownBy(() -> tx.finish(execution, "UNKNOWN", "CF-12302", null, null))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void terminalLegacyFailureSurvivesIdempotentCancellation() {
        var s = submitted();
        deliverCommand();
        when(provider.register(anyString(), any(), anyBoolean()))
                .thenReturn(CertificateProviderResult.rejected("REGISTRATION_REJECTED"));
        worker.executeOne();
        var event = tx.claimPublication(true).orElseThrow();
        tx.receiveResult(event.event(), caps.hash("legacy"));
        var state = registration.get(s.id(), s.pcCapability());
        assertThat(state.failureCode()).isEqualTo("BANK_CONNECTION_FAILED");
        assertThat(state.failureMessage()).isEqualTo("은행 연결에 실패했습니다.");
        registration.cancel(s.id(), s.pcCapability());
        assertThat(registration.get(s.id(), s.pcCapability()).status()).isEqualTo("FAILED");
    }

    @Test
    void yesBindsEncryptedUserConnectionAndReusesItOnNextCertificate() {
        var first = submitted();
        deliverCommand();
        successfulRegistration();
        assertThat(registration.get(first.id(), first.pcCapability()).status()).isEqualTo("BOUND");
        var consent = consents.find(first.id()).orElseThrow();
        assertThat(consent.connectionId()).isNotNull();
        var stored =
                jdbc.queryForObject(
                        "SELECT connected_id_ciphertext FROM external_connections WHERE user_id=?",
                        String.class,
                        user);
        assertThat(stored).startsWith("v1:").doesNotContain("connected-fixture");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM certificate_provider_connections WHERE"
                                    + " session_id=?",
                                String.class,
                                first.id()))
                .isEqualTo("BOUND");
        assertThat(
                        decisions
                                .decide(
                                        new DecideCertificateConnectionCommand(
                                                first.id(), consent.claimId(), true))
                                .status())
                .isEqualTo("BOUND");
        assertThatThrownBy(
                        () ->
                                decisions.decide(
                                        new DecideCertificateConnectionCommand(
                                                first.id(), consent.claimId(), false)))
                .isInstanceOf(ApplicationException.class);
        assertThatThrownBy(() -> registration.cancel(first.id(), first.pcCapability()))
                .isInstanceOf(ApplicationException.class);
        when(provider.update(anyString(), anyString(), any(), anyBoolean()))
                .thenReturn(CertificateProviderResult.connected("connected-fixture"));
        var second = submitted();
        deliverCommand();
        assertThat(consents.find(second.id()).orElseThrow().connectionId()).isNull();
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "UPDATE certificate_registration_consents SET"
                                            + " external_connection_id=? WHERE session_id=?",
                                        consent.connectionId(),
                                        second.id()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "UPDATE certificate_registration_consents SET"
                                            + " completed_at=approved_at WHERE session_id=?",
                                        second.id()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "UPDATE certificate_registration_consents SET"
                                            + " external_connection_id=?,"
                                            + " completed_at=approved_at - INTERVAL '1 microsecond'"
                                            + " WHERE session_id=?",
                                        consent.connectionId(),
                                        second.id()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        worker.executeOne();
        assertThat(registration.get(second.id(), second.pcCapability()).status())
                .isEqualTo("BOUND");
        assertThat(consents.find(second.id()).orElseThrow().connectionId())
                .isEqualTo(consent.connectionId());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT completed_at IS NOT NULL AND completed_at >= approved_at"
                                    + " FROM certificate_registration_consents WHERE session_id=?",
                                Boolean.class,
                                second.id()))
                .isTrue();
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "UPDATE certificate_registration_consents SET"
                                            + " external_connection_id=NULL, completed_at=NULL"
                                            + " WHERE session_id=?",
                                        second.id()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "UPDATE certificate_registration_consents SET"
                                            + " completed_at=completed_at + INTERVAL '1 second'"
                                            + " WHERE session_id=?",
                                        second.id()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        verify(provider, times(1)).register(anyString(), any(), anyBoolean());
        verify(provider, times(1)).update(eq("connected-fixture"), eq("0020"), any(), eq(true));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM external_connections WHERE user_id=?",
                                Integer.class,
                                user))
                .isEqualTo(1);
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "UPDATE certificate_registration_consents SET"
                                            + " user_id=user_id+1 WHERE session_id=?",
                                        first.id()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void noCancelsSecretsWithoutProviderRequestAndIsIdempotent() {
        var s = submitted();
        var qr = claimService.issue(s.id(), s.pcCapability());
        var claim =
                claimService.reserve(
                        new ReserveCertificateClaimCommand(
                                s.id(), qr.qrToken(), UUID.randomUUID()));
        var no = new DecideCertificateConnectionCommand(s.id(), claim.claimId(), false);
        assertThat(decisions.decide(no).status()).isEqualTo("CANCELLED");
        assertThat(decisions.decide(no).status()).isEqualTo("CANCELLED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT encrypted_payload IS NULL FROM certificate_upload_secrets"
                                    + " WHERE session_id=?",
                                Boolean.class,
                                s.id()))
                .isTrue();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM certificate_command_outbox", Integer.class))
                .isZero();
        verifyNoInteractions(provider);
        assertThatThrownBy(
                        () ->
                                decisions.decide(
                                        new DecideCertificateConnectionCommand(
                                                s.id(), claim.claimId(), true)))
                .isInstanceOf(ApplicationException.class);
    }

    @Test
    void anotherUserCannotInspectOrApproveClaim() {
        var s = submitted();
        var qr = claimService.issue(s.id(), s.pcCapability());
        var claim =
                claimService.reserve(
                        new ReserveCertificateClaimCommand(
                                s.id(), qr.qrToken(), UUID.randomUUID()));
        when(actor.getCurrentActor()).thenReturn(new CurrentActor(user + 1));
        assertThatThrownBy(() -> decisions.getDecision(s.id(), claim.claimId()))
                .isInstanceOf(ApplicationException.class);
        assertThatThrownBy(
                        () ->
                                decisions.decide(
                                        new DecideCertificateConnectionCommand(
                                                s.id(), claim.claimId(), true)))
                .isInstanceOf(ApplicationException.class);
        verifyNoInteractions(provider);
    }

    @Test
    void expiredClaimCannotApproveAndDoesNotDispatch() {
        var s = submitted();
        var qr = claimService.issue(s.id(), s.pcCapability());
        var c =
                claimService.reserve(
                        new ReserveCertificateClaimCommand(
                                s.id(), qr.qrToken(), UUID.randomUUID()));
        var lateClock = Clock.fixed(NOW.plusSeconds(61).toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
        var late =
                transactional(
                        new CertificateConnectionDecisionService(
                                storage,
                                claims,
                                consents,
                                storage,
                                storage,
                                actor,
                                mock(
                                        com.amateur.howmuchbackend.application.port.out.user
                                                .LoadUserPort.class),
                                lateClock));
        assertThatThrownBy(
                        () ->
                                late.decide(
                                        new DecideCertificateConnectionCommand(
                                                s.id(), c.claimId(), true)))
                .isInstanceOf(ApplicationException.class);
        assertThat(consents.find(s.id())).isEmpty();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM certificate_command_outbox", Integer.class))
                .isZero();
    }

    @Test
    void concurrentYesDecisionsProduceOneProviderCommand() throws Exception {
        var s = submitted();
        var qr = claimService.issue(s.id(), s.pcCapability());
        var c =
                claimService.reserve(
                        new ReserveCertificateClaimCommand(
                                s.id(), qr.qrToken(), UUID.randomUUID()));
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a =
                    pool.submit(
                            () ->
                                    decisions.decide(
                                            new DecideCertificateConnectionCommand(
                                                    s.id(), c.claimId(), true)));
            var b =
                    pool.submit(
                            () ->
                                    decisions.decide(
                                            new DecideCertificateConnectionCommand(
                                                    s.id(), c.claimId(), true)));
            assertThat(a.get().status()).isEqualTo("SUBMITTED");
            assertThat(b.get().status()).isEqualTo("SUBMITTED");
        }
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM certificate_command_outbox", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void existingConnectionUsesAddForUnregisteredBankAndNeverCreatesAnotherId() {
        submitted();
        deliverCommand();
        successfulRegistration();
        when(banks.registeredOrganizations(anyString())).thenReturn(Set.of());
        when(provider.add(anyString(), anyString(), any(), anyBoolean()))
                .thenReturn(CertificateProviderResult.connected("connected-fixture"));
        var s = submitted();
        deliverCommand();
        worker.executeOne();
        assertThat(registration.get(s.id(), s.pcCapability()).status()).isEqualTo("BOUND");
        verify(provider, times(1)).register(anyString(), any(), anyBoolean());
        verify(provider).add(eq("connected-fixture"), eq("0020"), any(), eq(true));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM external_connections WHERE user_id=?",
                                Integer.class,
                                user))
                .isEqualTo(1);
    }

    @Test
    void unexpectedProviderIdDoesNotOverwriteTheUsersExistingConnection() {
        var first = submitted();
        deliverCommand();
        successfulRegistration();
        var oldId = consents.find(first.id()).orElseThrow().connectionId();
        when(provider.update(anyString(), anyString(), any(), anyBoolean()))
                .thenReturn(CertificateProviderResult.connected("wrong-provider-fixture"));
        var second = submitted();
        deliverCommand();
        worker.executeOne();
        var result = tx.claimPublication(true).orElseThrow();
        tx.receiveResult(result.event(), caps.hash("unexpected"));
        assertThat(registration.get(second.id(), second.pcCapability()).status())
                .isEqualTo("UNKNOWN");
        assertThat(consents.find(second.id()).orElseThrow().connectionId()).isNull();
        assertThat(connections.findActiveByUserId(user).orElseThrow().id()).isEqualTo(oldId);
        assertThat(connections.findActiveByUserId(user).orElseThrow().connectedId())
                .isEqualTo("connected-fixture");
        worker.executeOne();
        verify(provider, times(1)).update(anyString(), anyString(), any(), anyBoolean());
    }

    @Test
    void storageFailureAfterProviderMutationIsUnknownAndIsNotRetried() {
        var s = submitted();
        deliverCommand();
        doThrow(new IllegalStateException("synthetic-storage-failure"))
                .when(connections)
                .saveNew(any(), any());
        worker.executeOne();
        worker.executeOne();
        var result = tx.claimPublication(true).orElseThrow();
        tx.receiveResult(result.event(), caps.hash("storage-failure"));
        assertThat(registration.get(s.id(), s.pcCapability()).status()).isEqualTo("UNKNOWN");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM external_connections WHERE user_id=?",
                                Integer.class,
                                user))
                .isZero();
        verify(provider, times(1)).register(anyString(), any(), anyBoolean());
    }

    @Test
    void commandWithoutMobileConsentCannotStartProviderRegistration() {
        var s = submitted();
        legacyCommand(s);
        worker.executeOne();
        verifyNoInteractions(provider);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT provider_request_started_at IS NULL FROM"
                                    + " certificate_provider_command_inbox WHERE session_id=?",
                                Boolean.class,
                                s.id()))
                .isTrue();
    }

    private org.apache.kafka.clients.consumer.ConsumerRecord<String, String> consume(String topic) {
        try (var consumer =
                new org.apache.kafka.clients.consumer.KafkaConsumer<String, String>(
                        Map.of(
                                "bootstrap.servers",
                                BROKER.getBootstrapServers(),
                                "group.id",
                                UUID.randomUUID().toString(),
                                "key.deserializer",
                                org.apache.kafka.common.serialization.StringDeserializer.class,
                                "value.deserializer",
                                org.apache.kafka.common.serialization.StringDeserializer.class,
                                "auto.offset.reset",
                                "earliest",
                                "enable.auto.commit",
                                false))) {
            consumer.subscribe(List.of(topic));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (System.nanoTime() < deadline) {
                var records = consumer.poll(Duration.ofMillis(200));
                if (!records.isEmpty()) return records.iterator().next();
            }
            throw new AssertionError("Kafka fixture did not receive the certificate message");
        }
    }

    ReceiveCertificateCommand legacyCommand(CertificateRegistrationResult s) {
        new TransactionTemplate(manager)
                .executeWithoutResult(
                        t -> {
                            jdbc.update(
                                    "UPDATE certificate_registration_sessions SET"
                                        + " status='SUBMITTED',qr_token_hash=NULL,qr_expires_at=NULL"
                                        + " WHERE id=?",
                                    s.id());
                            storage.enqueue(
                                    storage.find(s.id(), true).orElseThrow(), "REGISTER", NOW);
                        });
        var p = tx.claimPublication(false).orElseThrow();
        tx.published(p, true);
        tx.receiveCommand(p.command(), caps.hash("legacy-command"));
        return p.command();
    }

    CertificateRegistrationResult legacyReadyConnection() {
        var s = submitted();
        var command = legacyCommand(s);
        var execution = tx.claim().orElseThrow();
        new TransactionTemplate(manager)
                .executeWithoutResult(t -> jobs.startRequest(execution, NOW));
        UUID ref = UUID.randomUUID();
        var secret =
                protection.sealConnection(
                        s.id(),
                        ref,
                        new CertificateConnectionSecret("connected-fixture", null, null, null));
        tx.finish(execution, "SUCCEEDED", null, ref, secret);
        var result = tx.claimPublication(true).orElseThrow();
        tx.receiveResult(result.event(), caps.hash("legacy-result"));
        tx.published(result, true);
        return s;
    }

    @Test
    void cancelledUnapprovedConnectionIsDeletedAndItsSecretRemoved() {
        var s = legacyReadyConnection();
        registration.cancel(s.id(), s.pcCapability());
        var cancel = tx.claimPublication(false).orElseThrow();
        tx.receiveCommand(cancel.command(), caps.hash("cancel"));
        tx.published(cancel, true);
        when(provider.delete(anyString(), anyString())).thenReturn(true);
        worker.executeOne();
        verify(provider).delete("0020", "connected-fixture");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM certificate_provider_connections",
                                String.class))
                .isEqualTo("DISCARDED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT encrypted_payload IS NULL FROM"
                                        + " certificate_provider_connections",
                                Boolean.class))
                .isTrue();
    }

    @Test
    void uncertainDeletionRetainsEvidenceAndDoesNotAutomaticallyRepeat() {
        var s = legacyReadyConnection();
        registration.cancel(s.id(), s.pcCapability());
        var cancel = tx.claimPublication(false).orElseThrow();
        tx.receiveCommand(cancel.command(), caps.hash("cancel"));
        tx.published(cancel, true);
        when(provider.delete(anyString(), anyString())).thenReturn(false);
        worker.executeOne();
        worker.recover();
        worker.executeOne();
        verify(provider, times(1)).delete(anyString(), anyString());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM certificate_provider_connections",
                                String.class))
                .isEqualTo("DELETE_UNKNOWN");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM certificate_provider_command_inbox WHERE"
                                        + " command_type='CANCEL'",
                                String.class))
                .isEqualTo("UNKNOWN");
    }

    @Test
    void unresolvedRegistrationDoesNotSpinCancellationButLateSuccessCanBeCleaned() {
        var s = submitted();
        var command = legacyCommand(s);
        var execution = tx.claim().orElseThrow();
        assertThat(
                        (Boolean)
                                new TransactionTemplate(manager)
                                        .execute(t -> jobs.startRequest(execution, NOW)))
                .isTrue();
        tx.finish(execution, "UNKNOWN", "REGISTRATION_UNCERTAIN", null, null);
        registration.cancel(s.id(), s.pcCapability());
        var cancel = tx.claimPublication(false).orElseThrow();
        tx.receiveCommand(cancel.command(), caps.hash("cancel"));
        tx.published(cancel, true);
        assertThat(tx.claim()).isEmpty();
        UUID ref = UUID.randomUUID();
        var envelope =
                protection.sealConnection(
                        s.id(),
                        ref,
                        new CertificateConnectionSecret("connected-late", null, null, null));
        tx.finish(execution, "SUCCEEDED", null, ref, envelope);
        when(provider.delete(anyString(), anyString())).thenReturn(true);
        worker.executeOne();
        verify(provider).delete("0020", "connected-late");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM certificate_provider_connections",
                                String.class))
                .isEqualTo("DISCARDED");
    }
}
