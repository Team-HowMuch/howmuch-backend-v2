package com.amateur.howmuchbackend.application.account.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.amateur.howmuchbackend.adapter.in.kafka.account.CertificateReferenceKafkaListener;
import com.amateur.howmuchbackend.adapter.in.web.account.CertificateReferenceController;
import com.amateur.howmuchbackend.adapter.out.kafka.account.CertificateReferenceKafkaPublisher;
import com.amateur.howmuchbackend.adapter.out.persistence.account.*;
import com.amateur.howmuchbackend.adapter.out.security.CertificateSecretProtectionAdapter;
import com.amateur.howmuchbackend.application.account.catalog.BankCatalog;
import com.amateur.howmuchbackend.application.account.model.*;
import com.amateur.howmuchbackend.application.account.policy.*;
import com.amateur.howmuchbackend.application.auth.model.CurrentActor;
import com.amateur.howmuchbackend.application.port.in.account.command.StartCertificateReferenceConnectionCommand;
import com.amateur.howmuchbackend.application.port.in.certificate.result.CertificateInstitutionResult;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.application.port.out.certificate.CertificateInstitutionCatalogPort;
import com.amateur.howmuchbackend.application.port.out.external.*;
import com.amateur.howmuchbackend.application.port.out.user.LoadUserPort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.account.*;
import com.amateur.howmuchbackend.domain.external.ExternalConnection;
import com.amateur.howmuchbackend.domain.external.status.ExternalConnectionStatusCode;
import com.amateur.howmuchbackend.domain.user.User;
import com.amateur.howmuchbackend.infra.account.config.CertificateReferenceProperties;
import com.amateur.howmuchbackend.infra.certificate.config.CertificateProperties;
import com.amateur.howmuchbackend.infra.messaging.kafka.*;

import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.serialization.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.kafka.core.*;
import org.springframework.kafka.test.EmbeddedKafkaKraftBroker;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import software.amazon.awssdk.services.kms.KmsClient;

import tools.jackson.databind.json.JsonMapper;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Real PostgreSQL transactions and Kafka transport; financial provider is a deterministic fake. */
class CertificateReferenceFlowIntegrationTest {
    static GenericContainer<?> db;
    static JdbcTemplate jdbc;
    static DataSourceTransactionManager manager;
    static EmbeddedKafkaKraftBroker broker;
    static DefaultKafkaProducerFactory<String, String> producer;
    static KafkaTemplate<String, String> template;
    static final String TOPIC = "reference-flow-test";
    static final JsonMapper JSON = JsonMapper.builder().build();
    final MutableClock clock = new MutableClock();
    CertificateReferenceJdbcAdapter store;
    com.amateur.howmuchbackend.adapter.out.persistence.external.InstitutionAuthenticationJdbcAdapter
            authentication;
    FinancialConnectionMutationStateJdbcAdapter mutationState;
    CertificateSecretProtectionAdapter protection;
    CertificateReferenceTransactions tx;
    CertificateReferenceService service;
    CertificateReferenceWorkerService worker;
    BankCertificateReferencePort provider;
    BankCatalog bankCatalog;
    ExternalConnectionStorePort connections;
    CurrentActorPort actor;
    final AtomicBoolean targetRegistered = new AtomicBoolean();
    long user, connection;
    UUID idempotency;

    @BeforeAll
    static void infrastructure() throws Exception {
        String url = System.getenv("REFERENCE_TEST_DB_URL");
        String username = "postgres";
        if (url == null) {
            db =
                    new GenericContainer<>("postgres:16.14")
                            .withEnv("POSTGRES_HOST_AUTH_METHOD", "trust")
                            .withEnv("POSTGRES_DB", "reference_test")
                            .withExposedPorts(5432)
                            .waitingFor(
                                    Wait.forLogMessage(
                                            ".*database system is ready to accept connections.*\\n",
                                            2));
            db.start();
            url =
                    "jdbc:postgresql://"
                            + db.getHost()
                            + ":"
                            + db.getMappedPort(5432)
                            + "/reference_test";
        } else {
            // Opt-in local verification must use the disposable loopback database, never an app DB.
            assertThat(url).matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/reference_test");
            username =
                    Objects.requireNonNullElse(System.getenv("REFERENCE_TEST_DB_USER"), "ref_test");
        }
        var ds = new DriverManagerDataSource(url, username, "");
        jdbc = new JdbcTemplate(ds);
        manager = new DataSourceTransactionManager(ds);
        assertThat(jdbc.queryForObject("SELECT current_database()", String.class))
                .isEqualTo("reference_test");
        Flyway.configure().dataSource(ds).cleanDisabled(false).load().clean();
        Flyway.configure().dataSource(ds).load().migrate();
        broker = new EmbeddedKafkaKraftBroker(1, 1, TOPIC);
        broker.afterPropertiesSet();
        producer =
                new DefaultKafkaProducerFactory<>(
                        Map.of(
                                "bootstrap.servers",
                                broker.getBrokersAsString(),
                                "key.serializer",
                                StringSerializer.class,
                                "value.serializer",
                                StringSerializer.class,
                                "acks",
                                "all",
                                "enable.idempotence",
                                true));
        template = new KafkaTemplate<>(producer);
    }

    @AfterAll
    static void shutdown() {
        if (template != null) template.destroy();
        if (producer != null) producer.destroy();
        if (broker != null) broker.destroy();
        if (db != null) db.stop();
    }

    @SuppressWarnings("unchecked")
    static <T> T transactional(T target) {
        var proxy = new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(
                new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        return (T) proxy.getProxy();
    }

    @BeforeEach
    void setup() {
        jdbc.execute("TRUNCATE users CASCADE");
        user =
                jdbc.queryForObject(
                        """
INSERT INTO users(identity_key,name,public_id,phone_number,pin_hash,phone_verified,role,active,created_at,updated_at)
VALUES('fixture','참조 테스트','ref_test','01000000000','unused',true,'USER',true,?,?) RETURNING id
""",
                        Long.class,
                        now(),
                        now());
        connection =
                jdbc.queryForObject(
                        """
INSERT INTO external_connections(user_id,connected_id_ciphertext,configuration_fingerprint,active,created_at,updated_at)
VALUES(?,'synthetic-cipher','fixture',true,?,?) RETURNING id
""",
                        Long.class,
                        user,
                        now(),
                        now());
        idempotency = UUID.randomUUID();
        var users = mock(LoadUserPort.class);
        var domainUser = mock(User.class);
        when(domainUser.active()).thenReturn(true);
        when(users.findById(user)).thenReturn(Optional.of(domainUser));
        when(users.findByIdForUpdate(user))
                .thenAnswer(
                        i -> {
                            jdbc.queryForObject(
                                    "SELECT id FROM users WHERE id=? FOR UPDATE", Long.class, user);
                            return Optional.of(domainUser);
                        });
        actor = mock(CurrentActorPort.class);
        when(actor.getCurrentActor()).thenReturn(new CurrentActor(user));
        connections = mock(ExternalConnectionStorePort.class);
        var domainConnection = new ExternalConnection(connection, user, "provider-fixture");
        when(connections.findActiveByIdAndUserId(connection, user))
                .thenReturn(Optional.of(domainConnection));
        when(connections.findActiveByUserId(user)).thenReturn(Optional.of(domainConnection));
        when(connections.hasInstitution(eq(connection), anyString()))
                .thenAnswer(
                        i ->
                                jdbc.queryForObject(
                                        "SELECT EXISTS(SELECT 1 FROM connected_institutions WHERE"
                                                + " external_connection_id=? AND organization=?)",
                                        Boolean.class,
                                        connection,
                                        i.getArgument(1)));
        doAnswer(
                        i -> {
                            var institution =
                                    (com.amateur.howmuchbackend.domain.external
                                                    .ConnectedInstitution)
                                            i.getArgument(0);
                            jdbc.update(
                                    """
INSERT INTO connected_institutions(external_connection_id,organization,status,last_result_code,
account_credentials_local_ciphertext,created_at,updated_at) VALUES(?,?,'CONNECTED','CF-00000',?,?,?)
""",
                                    connection,
                                    institution.organization(),
                                    institution.localCredentialsCiphertext(),
                                    now(),
                                    now());
                            return null;
                        })
                .when(connections)
                .saveInstitution(any());
        String key = Base64.getEncoder().encodeToString(new byte[32]);
        protection =
                new CertificateSecretProtectionAdapter(
                        new CertificateProperties(
                                true, "", key, key, List.of(), null, null, null, null, null, null),
                        new StaticListableBeanFactory().getBeanProvider(KmsClient.class));
        store =
                transactional(
                        new CertificateReferenceJdbcAdapter(
                                jdbc,
                                new CertificateReferenceProperties(
                                        true, TOPIC, TOPIC + ".dlt", null, 4)));
        authentication =
                new com.amateur.howmuchbackend.adapter.out.persistence.external
                        .InstitutionAuthenticationJdbcAdapter(jdbc, clock);
        mutationState = new FinancialConnectionMutationStateJdbcAdapter(jdbc);
        var credentials = mock(AccountCredentialProtectionService.class);
        when(credentials.protect(anyLong(), anyString(), any()))
                .thenReturn(new EncryptedBankAccountCredentials("protected", null));
        tx =
                transactional(
                        new CertificateReferenceTransactions(
                                store,
                                connections,
                                users,
                                mutationState,
                                protection,
                                credentials,
                                clock,
                                authentication));
        CertificateInstitutionCatalogPort catalog =
                () ->
                        List.of(
                                new CertificateInstitutionResult(
                                        "0020", "우리은행", true, false, null, 3),
                                new CertificateInstitutionResult(
                                        "0088", "신한은행", true, false, null, 1));
        provider = mock(BankCertificateReferencePort.class);
        when(provider.registeredAccounts("provider-fixture"))
                .thenAnswer(
                        i ->
                                targetRegistered.get()
                                        ? List.of(
                                                new BankCertificateReferencePort.RegisteredAccount(
                                                        "0020",
                                                        com.amateur.howmuchbackend.domain.external
                                                                .BankAuthenticationMethod
                                                                .CERTIFICATE),
                                                new BankCertificateReferencePort.RegisteredAccount(
                                                        "0088",
                                                        com.amateur.howmuchbackend.domain.external
                                                                .BankAuthenticationMethod
                                                                .CERTIFICATE))
                                        : List.of(
                                                new BankCertificateReferencePort.RegisteredAccount(
                                                        "0020",
                                                        com.amateur.howmuchbackend.domain.external
                                                                .BankAuthenticationMethod
                                                                .CERTIFICATE)));
        doAnswer(
                        i -> {
                            targetRegistered.set(true);
                            return null;
                        })
                .when(provider)
                .addReference(anyString(), anyString(), anyString(), any());
        bankCatalog = mock(BankCatalog.class);
        when(bankCatalog.getAllBanks())
                .thenReturn(
                        List.of(
                                new com.amateur.howmuchbackend.application.port.in.account.result
                                        .BankCatalogResult("0020", "우리은행", "icon", 3),
                                new com.amateur.howmuchbackend.application.port.in.account.result
                                        .BankCatalogResult("0088", "신한은행", "icon", 1)));
        service =
                transactional(
                        new CertificateReferenceService(
                                actor,
                                users,
                                connections,
                                store,
                                protection,
                                authentication,
                                catalog,
                                bankCatalog,
                                new CertificateReferenceInputPolicy(),
                                new FinancialConnectionMutationGuard(
                                        mutationState, new FinancialConnectionMutationPolicy()),
                                clock));
        var publisher =
                new CertificateReferenceKafkaPublisher(
                        new AcknowledgedKafkaSender(template, Duration.ofSeconds(10)),
                        JSON,
                        new CertificateReferenceProperties(true, TOPIC, TOPIC + ".dlt", null, 4));
        worker =
                new CertificateReferenceWorkerService(
                        store,
                        protection,
                        publisher,
                        provider,
                        mock(BankConnectionPort.class),
                        catalog,
                        tx,
                        clock);
    }

    LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    StartCertificateReferenceConnectionCommand command() {
        return new StartCertificateReferenceConnectionCommand(
                connection, idempotency, "0020", "0088", null, null, null);
    }

    UUID accept() {
        return service.startCertificateReference(command()).operationId();
    }

    void deliver(UUID operation) {
        var message =
                jdbc.queryForObject(
                        "SELECT message_id FROM certificate_reference_command_outbox WHERE"
                                + " operation_id=?",
                        UUID.class,
                        operation);
        assertThat(service.receiveReference(message, operation, "a".repeat(64))).isTrue();
    }

    CertificateReferenceStatus status(UUID id) {
        return store.find(id, user).orElseThrow().status();
    }

    @Test
    void httpAcceptanceKafkaInboxAndAtomicConnectedResult() throws Exception {
        var mvc =
                MockMvcBuilders.standaloneSetup(
                                new CertificateReferenceController(service, service, service))
                        .build();
        String response =
                mvc.perform(
                                post("/api/v2/accounts/connections/"
                                                + connection
                                                + "/certificate-references")
                                        .header("Idempotency-Key", idempotency)
                                        .contentType("application/json")
                                        .content(
                                                "{\"sourceOrganization\":\"0020\",\"organization\":\"0088\"}"))
                        .andExpect(
                                org.springframework.test.web.servlet.result.MockMvcResultMatchers
                                        .status()
                                        .isAccepted())
                        .andExpect(jsonPath("$.data.status").value("QUEUED"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        UUID operation =
                UUID.fromString(
                        JSON.readTree(response).path("data").path("operationId").stringValue());
        verifyNoInteractions(provider);
        worker.publishOne();
        var listener = new CertificateReferenceKafkaListener(JSON, service);
        try (var consumer =
                new KafkaConsumer<String, String>(
                        Map.of(
                                "bootstrap.servers",
                                broker.getBrokersAsString(),
                                "group.id",
                                UUID.randomUUID().toString(),
                                "auto.offset.reset",
                                "earliest",
                                "enable.auto.commit",
                                false),
                        new StringDeserializer(),
                        new StringDeserializer())) {
            consumer.subscribe(List.of(TOPIC));
            ConsumerRecord<String, String> found = null;
            long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
            while (found == null && System.nanoTime() < deadline)
                for (var record : consumer.poll(Duration.ofMillis(200)))
                    if (operation.toString().equals(record.key())) found = record;
            assertThat(found).isNotNull();
            assertThat(found.value()).doesNotContain("provider-fixture", "password", "0088");
            listener.receive(found);
            listener.receive(found); // Kafka redelivery is harmless.
        }
        worker.executeOne();
        mvc.perform(get("/api/v2/accounts/certificate-reference-operations/" + operation))
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                                .isOk())
                .andExpect(jsonPath("$.data.status").value("CONNECTED"));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM connected_institutions WHERE"
                                        + " external_connection_id=? AND organization='0088'",
                                Integer.class,
                                connection))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT encrypted_payload IS NULL FROM"
                                        + " certificate_reference_operations WHERE id=?",
                                Boolean.class,
                                operation))
                .isTrue();
        verify(provider, times(1)).addReference(anyString(), eq("0020"), eq("0088"), any());
    }

    @Test
    void failedOutboxInsertRollsBackAdmission() {
        jdbc.execute(
                "ALTER TABLE certificate_reference_command_outbox ADD CONSTRAINT test_reject_outbox"
                        + " CHECK(false) NOT VALID");
        try {
            assertThatThrownBy(this::accept)
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM certificate_reference_operations",
                                    Integer.class))
                    .isZero();
        } finally {
            jdbc.execute(
                    "ALTER TABLE certificate_reference_command_outbox DROP CONSTRAINT"
                            + " test_reject_outbox");
        }
    }

    @Test
    void malformedKafkaContractCannotReachInbox() {
        var listener = new CertificateReferenceKafkaListener(JSON, service);
        assertThatThrownBy(
                        () ->
                                listener.receive(
                                        new ConsumerRecord<>(
                                                TOPIC,
                                                0,
                                                0,
                                                "wrong-key",
                                                "{\"schemaVersion\":1,\"messageType\":\"REFERENCE_CONNECT\",\"connectedId\":\"secret\"}")))
                .isInstanceOf(InvalidKafkaMessageException.class);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM certificate_reference_command_inbox",
                                Integer.class))
                .isZero();
    }

    @Test
    void simultaneousSameKeyAdmissionsReturnOneJobAndOutbox() throws Exception {
        try (var pool = Executors.newFixedThreadPool(4)) {
            List<Future<UUID>> requests = new ArrayList<>();
            for (int i = 0; i < 4; i++) requests.add(pool.submit(this::accept));
            var ids = new HashSet<UUID>();
            for (var request : requests) ids.add(request.get(10, TimeUnit.SECONDS));
            assertThat(ids).hasSize(1);
        }
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM certificate_reference_command_outbox",
                                Integer.class))
                .isEqualTo(1);
        verifyNoInteractions(provider);
    }

    @Test
    void crashBeforeProviderMarkerExpiresWithoutExecutingRemoteMutation() {
        UUID id = accept();
        deliver(id);
        assertThat(store.claim(now())).isPresent();
        clock.advance(Duration.ofMinutes(16));
        worker.recover();
        assertThat(status(id)).isEqualTo(CertificateReferenceStatus.EXPIRED);
        verifyNoInteractions(provider);
    }

    @Test
    void startedCrashReconcilesWithoutResendingMutation() {
        UUID id = accept();
        deliver(id);
        var execution = store.claim(now()).orElseThrow();
        assertThat(tx.start(execution)).isTrue();
        clock.advance(Duration.ofMinutes(16));
        worker.recover();
        assertThat(status(id)).isEqualTo(CertificateReferenceStatus.UNKNOWN);
        worker.executeOne();
        assertThat(status(id)).isEqualTo(CertificateReferenceStatus.UNKNOWN);
        verify(provider, never()).addReference(anyString(), anyString(), anyString(), any());
        targetRegistered.set(true);
        clock.advance(Duration.ofSeconds(31));
        worker.executeOne();
        assertThat(status(id)).isEqualTo(CertificateReferenceStatus.CONNECTED);
        verify(provider, never()).addReference(anyString(), anyString(), anyString(), any());
    }

    @Test
    void slowPreflightCannotStartMutationOutsideItsExecutionBudget() {
        UUID id = accept();
        deliver(id);
        when(provider.registeredAccounts("provider-fixture"))
                .thenAnswer(
                        i -> {
                            clock.advance(Duration.ofMinutes(5));
                            return List.of(
                                    new BankCertificateReferencePort.RegisteredAccount(
                                            "0020",
                                            com.amateur.howmuchbackend.domain.external
                                                    .BankAuthenticationMethod.CERTIFICATE));
                        });
        worker.executeOne();
        assertThat(status(id)).isEqualTo(CertificateReferenceStatus.EXPIRED);
        verify(provider, never()).addReference(anyString(), anyString(), anyString(), any());
    }

    @Test
    void lostProviderResponseUsesReadOnlyReconciliation() {
        UUID id = accept();
        deliver(id);
        doAnswer(
                        i -> {
                            targetRegistered.set(true);
                            throw new CertificateReferenceUncertainException();
                        })
                .when(provider)
                .addReference(anyString(), anyString(), anyString(), any());
        worker.executeOne();
        assertThat(status(id)).isEqualTo(CertificateReferenceStatus.UNKNOWN);
        clock.advance(Duration.ofSeconds(31));
        worker.executeOne();
        assertThat(status(id)).isEqualTo(CertificateReferenceStatus.CONNECTED);
        verify(provider, times(1)).addReference(anyString(), anyString(), anyString(), any());
    }

    @Test
    void localFailureAfterProviderSuccessRollsBackCompletionThenReconciles() {
        UUID id = accept();
        deliver(id);
        // Replace the first persistence call only; the next call writes a real row.
        var first = new AtomicBoolean(true);
        doAnswer(
                        i -> {
                            if (first.getAndSet(false))
                                throw new IllegalStateException("synthetic outage");
                            jdbc.update(
                                    "INSERT INTO"
                                        + " connected_institutions(external_connection_id,organization,status,created_at,updated_at)"
                                        + " VALUES(?,'0088','CONNECTED',?,?)",
                                    connection,
                                    now(),
                                    now());
                            return null;
                        })
                .when(connections)
                .saveInstitution(any());
        worker.executeOne();
        assertThat(status(id)).isEqualTo(CertificateReferenceStatus.UNKNOWN);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM connected_institutions", Integer.class))
                .isZero();
        clock.advance(Duration.ofSeconds(31));
        worker.executeOne();
        assertThat(status(id)).isEqualTo(CertificateReferenceStatus.CONNECTED);
        verify(provider, times(1)).addReference(anyString(), anyString(), anyString(), any());
    }

    @Test
    void staleLeaseCannotFinishOrWriteInstitution() {
        UUID id = accept();
        deliver(id);
        var old = store.claim(now()).orElseThrow();
        assertThat(tx.start(old)).isTrue();
        clock.advance(Duration.ofMinutes(16));
        worker.recover();
        var current = store.claim(now()).orElseThrow();
        assertThat(current.generation()).isGreaterThan(old.generation());
        tx.connected(
                old,
                null,
                false,
                com.amateur.howmuchbackend.domain.external.BankAuthenticationMethod.CERTIFICATE);
        assertThat(status(id)).isEqualTo(CertificateReferenceStatus.UNKNOWN);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM connected_institutions", Integer.class))
                .isZero();
        tx.connected(
                current,
                null,
                false,
                com.amateur.howmuchbackend.domain.external.BankAuthenticationMethod.CERTIFICATE);
        assertThat(status(id)).isEqualTo(CertificateReferenceStatus.CONNECTED);
    }

    @Test
    void additionalAuthenticationIsExplicitAndNeverAutoRetried() {
        UUID id = accept();
        deliver(id);
        doThrow(
                        ApplicationException.of(
                                ExternalConnectionStatusCode.CODEF_ADDITIONAL_AUTH_REQUIRED))
                .when(provider)
                .addReference(anyString(), anyString(), anyString(), any());
        worker.executeOne();
        assertThat(status(id)).isEqualTo(CertificateReferenceStatus.ACTION_REQUIRED);
        clock.advance(Duration.ofMinutes(16));
        worker.recover();
        worker.executeOne();
        verify(provider, times(1)).addReference(anyString(), anyString(), anyString(), any());
        assertThat(mutationState.hasActiveReference(user)).isTrue();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT encrypted_payload IS NULL FROM"
                                        + " certificate_reference_operations WHERE id=?",
                                Boolean.class,
                                id))
                .isTrue();
    }

    @Test
    void activeJobBlocksIdentityMutationAndCrossOwnedForeignKey() {
        UUID id = accept();
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "UPDATE external_connections SET"
                                                + " connected_id_ciphertext='changed' WHERE id=?",
                                        connection))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE users SET active=false WHERE id=?", user))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "UPDATE certificate_reference_operations SET"
                                                + " user_id=user_id+1 WHERE id=?",
                                        id))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void unknownInboxIdentityOrAlteredReplayIsRejected() {
        UUID id = accept();
        UUID message =
                jdbc.queryForObject(
                        "SELECT message_id FROM certificate_reference_command_outbox WHERE"
                                + " operation_id=?",
                        UUID.class,
                        id);
        assertThat(service.receiveReference(message, UUID.randomUUID(), "a".repeat(64))).isFalse();
        assertThat(service.receiveReference(message, id, "a".repeat(64))).isTrue();
        assertThat(service.receiveReference(message, id, "b".repeat(64))).isFalse();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM certificate_reference_command_inbox",
                                Integer.class))
                .isEqualTo(1);
    }

    @Test
    void knownRejectionClearsInputAndReleasesMutationGuard() {
        UUID id = accept();
        deliver(id);
        doThrow(
                        ApplicationException.of(
                                ExternalConnectionStatusCode
                                        .BANK_CERTIFICATE_REGISTRATION_REQUIRED))
                .when(provider)
                .addReference(anyString(), anyString(), anyString(), any());
        worker.executeOne();
        assertThat(status(id)).isEqualTo(CertificateReferenceStatus.FAILED);
        assertThat(service.getCertificateReferenceOperation(id).failureCode())
                .isEqualTo("BANK_CERTIFICATE_REGISTRATION_REQUIRED");
        assertThat(mutationState.hasActiveReference(user)).isFalse();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT encrypted_payload IS NULL FROM"
                                        + " certificate_reference_operations WHERE id=?",
                                Boolean.class,
                                id))
                .isTrue();
        idempotency = UUID.randomUUID();
        assertThat(accept()).isNotEqualTo(id);
    }

    @Test
    void existingIdPasswordTargetIsReconciledWithoutOverwritingRemoteAuthentication() {
        UUID id = accept();
        deliver(id);
        when(provider.registeredAccounts("provider-fixture"))
                .thenReturn(
                        List.of(
                                new BankCertificateReferencePort.RegisteredAccount(
                                        "0020",
                                        com.amateur.howmuchbackend.domain.external
                                                .BankAuthenticationMethod.CERTIFICATE),
                                new BankCertificateReferencePort.RegisteredAccount(
                                        "0088",
                                        com.amateur.howmuchbackend.domain.external
                                                .BankAuthenticationMethod.ID_PASSWORD)));
        worker.executeOne();
        assertThat(status(id)).isEqualTo(CertificateReferenceStatus.CONNECTED);
        assertThat(service.getCertificateReferenceOperation(id).alreadyConnected()).isTrue();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT authentication_method FROM connected_institutions WHERE"
                                    + " external_connection_id=? AND organization='0088'",
                                String.class,
                                connection))
                .isEqualTo("ID_PASSWORD");
        verify(provider, never()).addReference(anyString(), anyString(), anyString(), any());
    }

    @Test
    void sourceAuthenticationChangedToIdPasswordIsRejectedBeforeMutation() {
        UUID id = accept();
        deliver(id);
        when(provider.registeredAccounts("provider-fixture"))
                .thenReturn(
                        List.of(
                                new BankCertificateReferencePort.RegisteredAccount(
                                        "0020",
                                        com.amateur.howmuchbackend.domain.external
                                                .BankAuthenticationMethod.ID_PASSWORD)));
        worker.executeOne();
        assertThat(status(id)).isEqualTo(CertificateReferenceStatus.FAILED);
        assertThat(service.getCertificateReferenceOperation(id).failureCode())
                .isEqualTo("BANK_CERTIFICATE_REFERENCE_INVALID");
        verify(provider, never()).addReference(anyString(), anyString(), anyString(), any());
    }

    @Test
    void localAuthenticationMetadataChangesCandidateListWithoutCallingCodef() {
        jdbc.update(
                "INSERT INTO"
                    + " connected_institutions(external_connection_id,organization,status,created_at,updated_at)"
                    + " VALUES(?,'0020','CONNECTED',?,?)",
                connection,
                now(),
                now());
        assertThat(service.getCertificateReferences("0088").references())
                .isEmpty(); // Legacy rows are UNKNOWN.
        authentication.recordMethod(
                connection,
                "0020",
                com.amateur.howmuchbackend.domain.external.BankAuthenticationMethod.CERTIFICATE);
        assertThat(service.getCertificateReferences("0088").references())
                .extracting(r -> r.sourceOrganization())
                .containsExactly("0020");
        authentication.recordMethod(
                connection,
                "0020",
                com.amateur.howmuchbackend.domain.external.BankAuthenticationMethod.ID_PASSWORD);
        assertThat(service.getCertificateReferences("0088").references()).isEmpty();
        verifyNoInteractions(provider);
    }

    @Test
    void metadataRecordingFailureRollsBackConnectedResultAndInstitutionTogether() {
        UUID id = accept();
        deliver(id);
        jdbc.execute(
                "ALTER TABLE connected_institutions ADD CONSTRAINT test_reject_confirmed_method"
                    + " CHECK(authentication_method='UNKNOWN') NOT VALID");
        try {
            worker.executeOne();
            assertThat(status(id)).isEqualTo(CertificateReferenceStatus.UNKNOWN);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM connected_institutions", Integer.class))
                    .isZero();
        } finally {
            jdbc.execute(
                    "ALTER TABLE connected_institutions DROP CONSTRAINT"
                        + " test_reject_confirmed_method");
        }
        clock.advance(Duration.ofSeconds(31));
        worker.executeOne();
        assertThat(status(id)).isEqualTo(CertificateReferenceStatus.CONNECTED);
        assertThat(authentication.findCertificateOrganizations(connection)).containsExactly("0088");
        verify(provider, times(1)).addReference(anyString(), anyString(), anyString(), any());
    }

    @Test
    void priorAuthenticationMethodSurvivesARolledBackLoginTransaction() {
        jdbc.update(
                "INSERT INTO"
                    + " connected_institutions(external_connection_id,organization,status,created_at,updated_at)"
                    + " VALUES(?,'0020','CONNECTED',?,?)",
                connection,
                now(),
                now());
        authentication.recordMethod(
                connection,
                "0020",
                com.amateur.howmuchbackend.domain.external.BankAuthenticationMethod.CERTIFICATE);
        new org.springframework.transaction.support.TransactionTemplate(manager)
                .executeWithoutResult(
                        tx -> {
                            authentication.recordMethod(
                                    connection,
                                    "0020",
                                    com.amateur.howmuchbackend.domain.external
                                            .BankAuthenticationMethod.ID_PASSWORD);
                            tx.setRollbackOnly();
                        });
        assertThat(authentication.findCertificateOrganizations(connection)).containsExactly("0020");
    }

    @Test
    void oldSchemaKeepsExistingLoginPathsCompatibleUntilReviewedMigration() {
        new org.springframework.transaction.support.TransactionTemplate(manager)
                .executeWithoutResult(
                        tx -> {
                            jdbc.execute(
                                    "ALTER TABLE connected_institutions DROP COLUMN"
                                        + " authentication_method CASCADE, DROP COLUMN"
                                        + " authentication_method_confirmed_at CASCADE");
                            assertThat(authentication.findCertificateOrganizations(connection))
                                    .isEmpty();
                            assertThatCode(
                                            () ->
                                                    authentication.recordMethod(
                                                            connection,
                                                            "0020",
                                                            com.amateur.howmuchbackend.domain
                                                                    .external
                                                                    .BankAuthenticationMethod
                                                                    .CERTIFICATE))
                                    .doesNotThrowAnyException();
                            tx.setRollbackOnly();
                        });
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM information_schema.columns WHERE"
                                    + " table_name='connected_institutions' AND"
                                    + " column_name='authentication_method'",
                                Integer.class))
                .isEqualTo(1);
    }

    CertificateReferenceOperation reservation() {
        String unique = UUID.randomUUID().toString();
        long owner =
                jdbc.queryForObject(
                        "INSERT INTO"
                            + " users(identity_key,name,public_id,phone_number,pin_hash,phone_verified,role,active,created_at,updated_at)"
                            + " VALUES(?,'용량 테스트',?,?,'unused',true,'USER',true,?,?) RETURNING id",
                        Long.class,
                        unique,
                        "r_" + unique.replace("-", "").substring(0, 10),
                        unique.substring(0, 20),
                        now(),
                        now());
        long cid =
                jdbc.queryForObject(
                        "INSERT INTO"
                            + " external_connections(user_id,connected_id_ciphertext,configuration_fingerprint,active,created_at,updated_at)"
                            + " VALUES(?,'synthetic','fixture',true,?,?) RETURNING id",
                        Long.class,
                        owner,
                        now(),
                        now());
        return new CertificateReferenceOperation(
                UUID.randomUUID(),
                owner,
                cid,
                "0020",
                "0088",
                UUID.randomUUID(),
                "a".repeat(64),
                "b".repeat(64),
                CertificateReferenceStatus.QUEUED,
                false,
                null,
                now(),
                now().plusMinutes(10),
                null,
                null);
    }

    boolean reserve(CertificateReferenceOperation op) {
        return store.create(
                op,
                protection.sealReference(
                        op, new CertificateReferenceSecret("provider-fixture", null, null, null)));
    }

    @Test
    void concurrentAdmissionCannotExceedExecutionCapacityOrLeaveAnOutboxForRejectedWork()
            throws Exception {
        var operations = new ArrayList<CertificateReferenceOperation>();
        for (int i = 0; i < 8; i++) operations.add(reservation());
        try (var pool = Executors.newFixedThreadPool(8)) {
            var results = new ArrayList<java.util.concurrent.Future<Boolean>>();
            for (var op : operations) results.add(pool.submit(() -> reserve(op)));
            int admitted = 0;
            for (var result : results) if (result.get(10, TimeUnit.SECONDS)) admitted++;
            assertThat(admitted).isEqualTo(4);
        }
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM certificate_reference_operations",
                                Integer.class))
                .isEqualTo(4);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM certificate_reference_command_outbox",
                                Integer.class))
                .isEqualTo(4);
    }

    @Test
    void newWorkGetsPriorityAndUnknownReconciliationOnlyConsumesFreeCapacity() {
        UUID unknown = accept();
        deliver(unknown);
        var first = store.claim(now()).orElseThrow();
        assertThat(tx.start(first)).isTrue();
        tx.finish(first, CertificateReferenceStatus.UNKNOWN, "CERTIFICATE_REFERENCE_UNCERTAIN");
        clock.advance(Duration.ofSeconds(31));
        var queued = reservation();
        assertThat(reserve(queued)).isTrue();
        assertThat(store.receive(queued.id(), queued.id(), "a".repeat(64), now())).isTrue();
        var next = store.claim(now()).orElseThrow();
        assertThat(next.operation().id()).isEqualTo(queued.id());
        var reconciliation = store.claim(now()).orElseThrow();
        assertThat(reconciliation.operation().id()).isEqualTo(unknown);
        assertThat(reserve(reservation())).isTrue();
        assertThat(reserve(reservation())).isTrue();
        assertThat(reserve(reservation()))
                .isFalse(); // Running + leased UNKNOWN both consume a place.
    }

    static final class MutableClock extends Clock {
        Instant instant = Instant.parse("2026-10-16T00:00:00Z");

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        public Clock withZone(ZoneId zone) {
            return this;
        }

        public Instant instant() {
            return instant;
        }
    }
}
