package com.amateur.howmuchbackend.application.settlement.service;

import com.amateur.howmuchbackend.application.settlement.model.RecurringSettlementSnapshotChangedException;
import com.amateur.howmuchbackend.application.port.in.settlement.CreateRecurringSettlementFromFormUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.UpdateRecurringSettlementUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.DeleteRecurringSettlementUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.command.WriteRecurringSettlementCommand;
import com.amateur.howmuchbackend.adapter.in.batch.settlement.RecurringSettlementFailureClassifier;
import java.util.Optional;
import java.time.DayOfWeek;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.mockito.Mockito;

import com.amateur.howmuchbackend.adapter.out.persistence.external.CodefSecretCipher;
import com.amateur.howmuchbackend.adapter.out.persistence.settlement.RecurringSettlementPersistenceAdapter;
import com.amateur.howmuchbackend.application.auth.model.CurrentActor;
import com.amateur.howmuchbackend.application.port.in.settlement.CancelRecurringSettlementUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.CreateRecurringSettlementUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.DispatchRecurringSettlementsUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.GetGroupRecurringSettlementsUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.GetRecurringSettlementHomeUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.GetRecurringSettlementOccurrencesUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.GetRecurringSettlementUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.ProcessRecurringSettlementUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.RecordRecurringSettlementFailureUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.ReplayRecurringSettlementUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.RequestSettlementUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.command.CreateRecurringSettlementCommand;
import com.amateur.howmuchbackend.application.port.in.settlement.command.RecurringSettlementPageCommand;
import com.amateur.howmuchbackend.application.port.in.settlement.result.RecurringSettlementExecutionResult;
import com.amateur.howmuchbackend.application.port.in.settlement.result.RecurringSettlementOccurrenceResult;
import com.amateur.howmuchbackend.application.port.in.settlement.result.RecurringSettlementPageResult;
import com.amateur.howmuchbackend.application.port.in.settlement.result.RecurringSettlementResult;
import com.amateur.howmuchbackend.application.port.in.settlement.result.RecurringSettlementSummaryResult;
import com.amateur.howmuchbackend.application.settlement.model.RecurringSettlementScope;
import com.amateur.howmuchbackend.application.settlement.model.RecurringSettlementStatusFilter;
import com.amateur.howmuchbackend.application.settlement.netting.service.GroupNettingInputReader;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadRecurringSettlementPort;
import com.amateur.howmuchbackend.application.port.out.settlement.RecurringSettlementExecutionPort;
import com.amateur.howmuchbackend.application.port.out.settlement.RecurringSettlementOccurrenceStorePort;
import com.amateur.howmuchbackend.application.port.out.settlement.netting.NettingInput;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.settlement.recurring.RecurrenceSchedule;
import com.amateur.howmuchbackend.domain.settlement.recurring.RecurringSettlement;
import com.amateur.howmuchbackend.domain.settlement.recurring.RecurringSettlementDisplayState;
import com.amateur.howmuchbackend.infra.codef.config.CodefConfigurationFingerprint;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.batch.autoconfigure.BatchAutoConfiguration;
import org.springframework.boot.batch.autoconfigure.BatchJobLauncherAutoConfiguration;
import org.springframework.boot.batch.jdbc.autoconfigure.BatchJdbcAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 실제 PostgreSQL·Redis에서 반복 정산 등록·발행·재시도·동시성·API 매핑을 검증한다.
 *
 * <p>Docker(Testcontainers)가 없는 환경을 전제로 하므로, 명시적 환경 변수 {@code RECURRING_TEST_DB_URL}이
 * 지정된 격리 DB에서만 실행한다. 지정이 없으면 클래스 전체가 비활성화되어 개발 DB에 연결하지 않는다.
 */
@SpringBootTest(useMainMethod = SpringBootTest.UseMainMethod.ALWAYS, properties = {
        "howmuch.settlement.recurring.enabled=true", "howmuch.settlement.recurring.poll-enabled=false",
        "howmuch.settlement.netting.enabled=false", "spring.batch.job.enabled=false",
        "howmuch.settlement.notification-outbox.enabled=false", "howmuch.settlement.automatic-reminder.enabled=false",
        "howmuch.auth.sms.discord.webhook-url=http://127.0.0.1:9/unused",
        "howmuch.codef.connected-id-encryption-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="
})
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "RECURRING_TEST_DB_URL", matches = ".+")
@Import(RecurringSettlementFlowIntegrationTest.ClockConfiguration.class)
class RecurringSettlementFlowIntegrationTest {
    private static final LocalDateTime CREATED = LocalDateTime.of(2026, 10, 13, 0, 0);
    private static final LocalDateTime FIRST = LocalDateTime.of(2026, 10, 31, 0, 0);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("RECURRING_TEST_DB_URL"));
        registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("RECURRING_TEST_DB_USERNAME", ""));
        registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("RECURRING_TEST_DB_PASSWORD", ""));
        if (System.getenv("RECURRING_TEST_REDIS_HOST") != null) {
            registry.add("spring.data.redis.host", () -> System.getenv("RECURRING_TEST_REDIS_HOST"));
        }
        if (System.getenv("RECURRING_TEST_REDIS_PORT") != null) {
            registry.add("spring.data.redis.port", () -> System.getenv("RECURRING_TEST_REDIS_PORT"));
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ClockConfiguration {
        @Bean
        @org.springframework.context.annotation.Primary
        MutableClock recurringTestClock() {
            return new MutableClock();
        }
    }

    static class MutableClock extends Clock {
        private final AtomicReference<Instant> instant = new AtomicReference<>(CREATED.toInstant(ZoneOffset.UTC));

        void at(LocalDateTime time) {
            instant.set(time.toInstant(ZoneOffset.UTC));
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(instant(), zone);
        }

        @Override
        public Instant instant() {
            return instant.get();
        }
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired MutableClock clock;
    @Autowired CreateRecurringSettlementUseCase create;
    @Autowired CreateRecurringSettlementFromFormUseCase createForm;
    @Autowired UpdateRecurringSettlementUseCase updateForm;
    @Autowired DeleteRecurringSettlementUseCase deleteForm;
    @Autowired GetRecurringSettlementUseCase get;
    @Autowired GetGroupRecurringSettlementsUseCase list;
    @Autowired GetRecurringSettlementHomeUseCase home;
    @Autowired GetRecurringSettlementOccurrencesUseCase history;
    @Autowired CancelRecurringSettlementUseCase cancel;
    @Autowired ProcessRecurringSettlementUseCase worker;
    @Autowired DispatchRecurringSettlementsUseCase dispatcher;
    @Autowired ReplayRecurringSettlementUseCase replay;
    @Autowired RequestSettlementUseCase immediate;
    @Autowired LoadRecurringSettlementPort recurring;
    @Autowired RecurringSettlementOccurrenceStorePort occurrences;
    @Autowired RecurringSettlementExecutionPort executions;
    @Autowired GroupNettingInputReader nettingInputs;
    @Autowired CodefSecretCipher cipher;
    @Autowired CodefConfigurationFingerprint fingerprint;
    @Autowired MockMvc mvc;
    @Autowired com.amateur.howmuchbackend.application.port.in.account.DeleteAccountUseCase deleteAccount;
    @MockitoBean CurrentActorPort actor;
    @MockitoSpyBean RecurringSettlementPersistenceAdapter persistence;
    @MockitoSpyBean RecurringSettlementFailureRecorder failureRecorder;
    @Autowired JobRepository repository;

    @BeforeEach
    void resetClockAndRecurringFixtures() {
        clock.at(CREATED);
        // Dedicated external test database; never touches a shared development database.
        jdbc.execute("TRUNCATE recurring_settlement_occurrences, recurring_settlement_participants,"
                + " recurring_settlement_expenses, recurring_settlements");
    }

    @Test
    void registration_waitsUntilFirstDate_thenCopiesAmountsAndUsesExistingRequestOutbox() {
        var fixture = fixture();
        var registered = register(fixture);
        assertThat(count("settlement_shares", "settlement_id", fixture.source())).isZero();
        assertThat(count("settlement_links", "settlement_id", fixture.source())).isZero();
        assertThat(jdbc.queryForObject("SELECT deleted_at IS NOT NULL FROM settlements WHERE id=?", Boolean.class,
                fixture.source())).isTrue();
        assertThat(dispatcher.dispatch(50)).isZero();
        assertThat(count("recurring_settlement_occurrences", "recurring_settlement_id", registered.id())).isZero();

        clock.at(FIRST.plusMinutes(1));
        assertThat(dispatcher.dispatch(50)).isEqualTo(1);
        var occurrence = occurrences.findBySchedule(registered.id(), FIRST).orElseThrow();
        assertThat(jdbc.queryForObject("SELECT status FROM settlements WHERE id=?", String.class,
                occurrence.settlementId())).isEqualTo("AWAITING_DEPOSIT");
        assertThat(jdbc.queryForList("SELECT amount FROM settlement_shares WHERE settlement_id=? ORDER BY amount",
                Long.class, occurrence.settlementId())).containsExactly(8_000L, 12_000L);
        assertThat(jdbc.queryForObject("""
                SELECT display_name FROM settlement_participants
                WHERE settlement_id=? AND linked_user_id=? AND source='REGISTERED_USER'
                  AND source_group_id IS NULL AND display_order=0
                """, String.class, occurrence.settlementId(), fixture.creator()))
                .isEqualTo(jdbc.queryForObject("SELECT name FROM users WHERE id=?", String.class,
                        fixture.creator()));
        assertThat(jdbc.queryForObject("""
                SELECT display_name FROM settlement_participants
                WHERE settlement_id=? AND linked_user_id=? AND source='GROUP_MEMBER'
                  AND source_group_id=? AND display_order=1
                """, String.class, occurrence.settlementId(), fixture.member(), fixture.group()))
                .isEqualTo(jdbc.queryForObject("SELECT name FROM users WHERE id=?", String.class,
                        fixture.member()));
        assertThat(count("settlement_participants", "settlement_id", occurrence.settlementId()))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT a.amount FROM settlement_dutch_pay_allocations a"
                        + " JOIN settlement_participants p ON p.id=a.participant_id"
                        + " WHERE p.settlement_id=? AND p.linked_user_id=?", Long.class,
                occurrence.settlementId(), fixture.creator())).isEqualTo(12_000L);
        assertThat(jdbc.queryForObject("SELECT a.amount FROM settlement_dutch_pay_allocations a"
                        + " JOIN settlement_participants p ON p.id=a.participant_id"
                        + " WHERE p.settlement_id=? AND p.linked_user_id=?", Long.class,
                occurrence.settlementId(), fixture.member())).isEqualTo(8_000L);
        // The netting reader accepts exactly one REGISTERED_USER creator with no source group and
        // GROUP_MEMBER parties for this group. Reading the published settlement as an estimate
        // proves the worker output satisfies that contract without NETTING_INVALID_SOURCE.
        var netting = nettingInputs.readForEstimate(fixture.group());
        assertThat(netting.sources()).extracting(NettingInput.Source::settlementId)
                .containsExactly(occurrence.settlementId());
        assertThat(netting.sources().getFirst().parties())
                .extracting(NettingInput.Party::userId, NettingInput.Party::source,
                        NettingInput.Party::sourceGroupId)
                .containsExactlyInAnyOrder(
                        tuple(fixture.creator(), "REGISTERED_USER", null),
                        tuple(fixture.member(), "GROUP_MEMBER", fixture.group()));
        assertThat(netting.debts()).singleElement().satisfies(debt -> {
            assertThat(debt.payerId()).isEqualTo(fixture.member());
            assertThat(debt.receiverId()).isEqualTo(fixture.creator());
            assertThat(debt.remainingAmount()).isEqualTo(8_000L);
        });
        assertThat(count("settlement_receiving_account_snapshots", "settlement_id", occurrence.settlementId()))
                .isEqualTo(1);
        assertThat(count("settlement_links", "settlement_id", occurrence.settlementId())).isEqualTo(1);
        assertThat(count("settlement_categories", "settlement_id", occurrence.settlementId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT source FROM settlement_categories WHERE settlement_id=?",
                String.class, occurrence.settlementId())).isEqualTo("RECURRING");
        assertThat(count("user_notifications", "settlement_id", occurrence.settlementId())).isEqualTo(1);
        assertThat(count("settlement_notification_outbox", "settlement_id", occurrence.settlementId())).isEqualTo(1);
        assertThat(get.get(fixture.group(), registered.id()).nextRunAt())
                .isEqualTo(LocalDateTime.of(2026, 11, 30, 0, 0));
        assertThat(executions.inspect(registered.id(), FIRST).state())
                .isEqualTo(RecurringSettlementExecutionPort.State.COMPLETED);
        assertThat(create.create(fixture.group(), command(fixture.source())).id()).isEqualTo(registered.id());
        assertThat(worker.process(registered.id(), FIRST))
                .isEqualTo(RecurringSettlementExecutionResult.ALREADY_PUBLISHED);
    }

    @Test
    void concurrentRegistration_returnsOneSettingAndOneConsumedDraft() throws Exception {
        var fixture = fixture();
        var results = concurrent(() -> create.create(fixture.group(), command(fixture.source())),
                () -> create.create(fixture.group(), command(fixture.source())));
        assertThat(results).extracting(value -> ((RecurringSettlementResult) value).id())
                .containsOnly(((RecurringSettlementResult) results.getFirst()).id());
        assertThat(count("recurring_settlements", "source_settlement_id", fixture.source())).isEqualTo(1);
    }

    @Test
    void registrationAndImmediateRequest_areMutuallyExclusive() throws Exception {
        var fixture = fixture();
        concurrent(() -> attempt(() -> create.create(fixture.group(), command(fixture.source()))),
                () -> attempt(() -> immediate.request(fixture.source())));
        int registered = count("recurring_settlements", "source_settlement_id", fixture.source());
        int shares = count("settlement_shares", "settlement_id", fixture.source());
        assertThat(registered == 1 && shares == 0 || registered == 0 && shares == 2).isTrue();
    }

    @Test
    void concurrentIssuance_commitsOnlyOneSettlement() throws Exception {
        var fixture = fixture();
        var value = register(fixture);
        clock.at(FIRST.plusMinutes(1));
        var results = concurrent(() -> worker.process(value.id(), FIRST), () -> worker.process(value.id(), FIRST));
        assertThat(results).containsExactlyInAnyOrder(RecurringSettlementExecutionResult.PUBLISHED,
                RecurringSettlementExecutionResult.ALREADY_PUBLISHED);
        assertThat(count("recurring_settlement_occurrences", "recurring_settlement_id", value.id())).isEqualTo(1);
        assertThat(newSettlementCount(fixture)).isEqualTo(1);
    }

    @Test
    void transientFailure_rollsBackEverything_stopsAtThree_thenAllowsManualReplay() {
        var fixture = fixture();
        var value = register(fixture);
        failAfterRequestWasWritten();
        clock.at(FIRST.plusMinutes(1));
        for (int attempt = 1; attempt <= 3; attempt++) {
            assertThat(dispatcher.dispatch(50)).isEqualTo(1);
            var current = recurring.findById(value.id()).orElseThrow();
            assertThat(current.failureCount()).isEqualTo(attempt);
            assertThat(current.nextRunAt()).isEqualTo(FIRST);
            assertThat(newSettlementCount(fixture)).isZero();
            assertThat(count("recurring_settlement_occurrences", "recurring_settlement_id", value.id())).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM user_notifications WHERE recipient_user_id=?",
                    Integer.class, fixture.member())).isZero();
            if (attempt < 3) {
                var metadataRetry = executions.inspect(value.id(), FIRST).lastAttemptAt()
                        .plusMinutes(attempt == 1 ? 1 : 5);
                clock.at(current.retryAt().isAfter(metadataRetry) ? current.retryAt() : metadataRetry);
            }
        }
        assertThat(dispatcher.dispatch(50)).isZero();
        assertThat(executions.inspect(value.id(), FIRST).automaticAttempts()).isEqualTo(3);
        assertThatThrownBy(() -> replay.replay(value.id(), FIRST, false)).isInstanceOf(IllegalStateException.class);
        assertThat(recurring.findById(value.id()).orElseThrow().failureCount()).isEqualTo(3);
        assertThat(executions.inspect(value.id(), FIRST).automaticAttempts()).isEqualTo(3);
        doCallRealMethod().when(persistenceTarget()).update(any());
        replay.replay(value.id(), FIRST, false);
        assertThat(newSettlementCount(fixture)).isEqualTo(1);
        assertThat(recurring.findById(value.id()).orElseThrow().failureCount()).isZero();
        assertThat(executions.inspect(value.id(), FIRST).automaticAttempts()).isEqualTo(3);
        assertThat(executions.inspect(value.id(), FIRST).lastAttemptAutomatic()).isFalse();
        assertThat(jdbc.queryForObject("""
                SELECT count(DISTINCT e.job_instance_id) FROM batch_job_execution e
                JOIN batch_job_execution_params p ON p.job_execution_id=e.job_execution_id
                WHERE p.parameter_name='recurringSettlementId' AND p.parameter_value=?
                """, Integer.class, value.id().toString())).isEqualTo(1);
    }

    @Test
    void concurrentLastAutomaticAttempts_cannotStartAFourthExecution() throws Exception {
        var fixture = fixture();
        var value = register(fixture);
        failAfterRequestWasWritten();
        clock.at(FIRST.plusMinutes(1));
        assertThat(dispatcher.dispatch(50)).isEqualTo(1);
        var current = recurring.findById(value.id()).orElseThrow();
        var metadataRetry = executions.inspect(value.id(), FIRST).lastAttemptAt().plusMinutes(1);
        clock.at(current.retryAt().isAfter(metadataRetry) ? current.retryAt() : metadataRetry);
        assertThat(dispatcher.dispatch(50)).isEqualTo(1);
        concurrent(() -> executions.launch(value.id(), FIRST, RecurringSettlementExecutionPort.Mode.AUTOMATIC, 3),
                () -> executions.launch(value.id(), FIRST, RecurringSettlementExecutionPort.Mode.AUTOMATIC, 3));
        assertThat(executions.inspect(value.id(), FIRST).automaticAttempts()).isEqualTo(3);
        assertThat(recurring.findById(value.id()).orElseThrow().failureCount()).isEqualTo(3);
        assertThat(newSettlementCount(fixture)).isZero();
    }

    @Test
    void lostFailureSummary_isStillBoundedByBatchExecutionHistory() {
        var fixture = fixture();
        var value = register(fixture);
        failAfterRequestWasWritten();
        doThrow(new IllegalStateException("synthetic summary write failure"))
                .when(AopTestUtils.<RecurringSettlementFailureRecorder>getUltimateTargetObject(failureRecorder))
                .record(any());
        clock.at(FIRST.plusMinutes(1));
        for (int attempt = 1; attempt <= 3; attempt++) {
            assertThat(dispatcher.dispatch(50)).isEqualTo(1);
            // Metadata timestamps use the real UTC clock, not the business test Clock.
            var metadataRetry = executions.inspect(value.id(), FIRST).lastAttemptAt()
                    .plusMinutes(attempt == 1 ? 1 : 5);
            clock.at(metadataRetry.isAfter(FIRST) ? metadataRetry : FIRST.plusMinutes(1));
        }
        assertThat(recurring.findById(value.id()).orElseThrow().failureCount()).isZero();
        assertThat(executions.inspect(value.id(), FIRST).automaticAttempts()).isEqualTo(3);
        assertThat(dispatcher.dispatch(50)).isZero();
        assertThat(newSettlementCount(fixture)).isZero();
    }

    @Test
    void committedBusinessWithStaleBatchMetadata_replaysWithoutDuplicatingAnyBusinessRows() {
        var fixture = fixture();
        var value = register(fixture);
        staleExecution(value.id());
        clock.at(FIRST.plusMinutes(1));
        worker.process(value.id(), FIRST);
        assertThat(newSettlementCount(fixture)).isEqualTo(1);
        // Simulate process loss between the committed business transaction and Batch completion.
        replay.replay(value.id(), FIRST, true);
        assertThat(newSettlementCount(fixture)).isEqualTo(1);
        assertThat(count("recurring_settlement_occurrences", "recurring_settlement_id", value.id())).isEqualTo(1);
        assertThat(recurring.findById(value.id()).orElseThrow().nextRunAt())
                .isEqualTo(LocalDateTime.of(2026, 11, 30, 0, 0));
    }

    @Test
    void unexpectedFailureWithLostSummary_requiresManualReviewWithoutAutomaticRestart() {
        var fixture = fixture();
        var value = register(fixture);
        failAfterRequestWasWritten();
        clock.at(FIRST.plusMinutes(1));
        assertThat(dispatcher.dispatch(50)).isEqualTo(1);
        var previousFailure = recurring.findById(value.id()).orElseThrow();
        var metadataRetry = executions.inspect(value.id(), FIRST).lastAttemptAt().plusMinutes(1);
        clock.at(previousFailure.retryAt().isAfter(metadataRetry) ? previousFailure.retryAt() : metadataRetry);
        doAnswer(invocation -> {
            RecurringSettlement current = invocation.getArgument(0);
            if (current.nextRunAt() != null && current.nextRunAt().isAfter(FIRST)) {
                throw new IllegalStateException("synthetic non-transient failure");
            }
            return invocation.callRealMethod();
        }).when(persistenceTarget()).update(any());
        doThrow(new IllegalStateException("synthetic summary failure"))
                .when(AopTestUtils.<RecurringSettlementFailureRecorder>getUltimateTargetObject(failureRecorder))
                .record(any());
        assertThat(dispatcher.dispatch(50)).isEqualTo(1);
        assertThat(recurring.findById(value.id()).orElseThrow().lastFailureCode())
                .isEqualTo(RecurringSettlement.TRANSIENT_FAILURE);
        clock.at(LocalDateTime.now(clock).plusYears(1));
        assertThat(dispatcher.dispatch(50)).isZero();
        assertThat(executions.inspect(value.id(), FIRST).automaticAttempts()).isEqualTo(2);
        assertThat(newSettlementCount(fixture)).isZero();
    }

    @Test
    void staleStartedExecution_isNotAutomaticallyRecovered() {
        var fixture = fixture();
        var value = register(fixture);
        JobExecution execution = staleExecution(value.id());
        clock.at(FIRST.plusMinutes(1));
        assertThat(dispatcher.dispatch(50)).isZero();
        assertThat(newSettlementCount(fixture)).isZero();
        assertThat(repository.getJobExecution(execution.getId()).getStatus()).isEqualTo(BatchStatus.STARTED);
        // This execution has no live worker: explicit operator confirmation is safe in this fixture.
        replay.replay(value.id(), FIRST, true);
        assertThat(newSettlementCount(fixture)).isEqualTo(1);
    }

    @Test
    void deletedAccount_doesNotBreakTemplateForeignKey_andCancelsOnNextRun() {
        var fixture = fixture();
        var value = register(fixture);
        deleteAccount.delete(fixture.account());
        assertThat(count("account_records", "id", fixture.account())).isEqualTo(1);
        clock.at(FIRST.plusMinutes(1));
        assertThat(worker.process(value.id(), FIRST)).isEqualTo(RecurringSettlementExecutionResult.CANCELLED);
        assertThat(recurring.findById(value.id()).orElseThrow().cancellationReason())
                .isEqualTo(RecurringSettlement.CancellationReason.ACCOUNT_UNAVAILABLE);
        assertThat(newSettlementCount(fixture)).isZero();
    }

    @Test
    void exitedMember_cancelsWholeSettingWithoutRedistributing() {
        var fixture = fixture();
        var value = register(fixture);
        jdbc.update("UPDATE group_members SET status='LEFT',left_at=?,updated_at=? WHERE group_id=? AND user_id=?",
                CREATED.plusDays(1), CREATED.plusDays(1), fixture.group(), fixture.member());
        clock.at(FIRST.plusMinutes(1));
        assertThat(worker.process(value.id(), FIRST)).isEqualTo(RecurringSettlementExecutionResult.CANCELLED);
        assertThat(recurring.findById(value.id()).orElseThrow().cancellationReason())
                .isEqualTo(RecurringSettlement.CancellationReason.PARTICIPANT_UNAVAILABLE);
        assertThat(newSettlementCount(fixture)).isZero();
    }

    @Test
    void registrationRollback_preservesTheOriginalDraftAndRemovesPartialSnapshots() {
        var fixture = fixture();
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new IllegalStateException("synthetic registration failure");
        }).when(persistenceTarget()).save(any());
        assertThatThrownBy(() -> register(fixture)).isInstanceOf(IllegalStateException.class);
        assertThat(count("recurring_settlements", "source_settlement_id", fixture.source())).isZero();
        assertThat(jdbc.queryForObject("SELECT deleted_at IS NULL FROM settlements WHERE id=?", Boolean.class,
                fixture.source())).isTrue();
    }

    @Test
    void permissionsAndCancellation_preserveIssuedSettlements() throws Exception {
        var fixture = fixture();
        var value = register(fixture);
        mvc.perform(get("/api/v2/groups/{group}/recurring-settlements", fixture.group()))
                .andExpect(status().isUnauthorized());
        when(actor.getCurrentActor()).thenReturn(new CurrentActor(fixture.member()));
        assertThat(get.get(fixture.group(), value.id()).id()).isEqualTo(value.id());
        assertThatThrownBy(() -> cancel.cancel(fixture.group(), value.id())).isInstanceOf(ApplicationException.class);
        when(actor.getCurrentActor()).thenReturn(new CurrentActor(fixture.creator()));
        clock.at(FIRST.plusMinutes(1));
        worker.process(value.id(), FIRST);
        var issued = occurrences.findBySchedule(value.id(), FIRST).orElseThrow();
        var cancelled = cancel.cancel(fixture.group(), value.id());
        assertThat(cancel.cancel(fixture.group(), value.id()).cancelledAt()).isEqualTo(cancelled.cancelledAt());
        assertThat(jdbc.queryForObject("SELECT status FROM settlements WHERE id=?", String.class,
                issued.settlementId())).isEqualTo("AWAITING_DEPOSIT");
        clock.at(FIRST.plusYears(1));
        assertThat(dispatcher.dispatch(50)).isZero();
    }

    @Test
    void apiRegistration_mapsTheExistingDraftAndRejectsInvalidWeeklyShape() throws Exception {
        var fixture = fixture();
        mvc.perform(post("/api/v2/groups/{group}/recurring-settlements", fixture.group()).with(user("signed"))
                        .contentType("application/json").content("""
                        {"sourceSettlementId":"%s","frequency":"MONTHLY","dayOfMonth":31,
                         "firstRunDate":"2026-10-31","categoryCode":"HOUSING_TELECOM"}
                        """.formatted(fixture.source())))
                .andExpect(status().isOk()).andExpect(jsonPath("data.title").value("반복 월세"))
                .andExpect(jsonPath("data.categoryCode").value("HOUSING_TELECOM"))
                .andExpect(jsonPath("data.schedule.timezone").value("Asia/Seoul"));
        mvc.perform(post("/api/v2/groups/{group}/recurring-settlements", fixture.group()).with(user("signed"))
                        .contentType("application/json").content("""
                        {"sourceSettlementId":"%s","frequency":"WEEKLY","dayOfMonth":31,
                         "firstRunDate":"2026-10-31","categoryCode":"HOUSING_TELECOM"}
                        """.formatted(fixture.source())))
                .andExpect(status().isBadRequest());
    }

    @Test
    void sharedBatchInfrastructure_supportsAllFourFeatureFlagCombinationsWithoutStartupJobs() {
        for (boolean netting : new boolean[]{false, true}) {
            for (boolean repeating : new boolean[]{false, true}) {
                new ApplicationContextRunner()
                        .withConfiguration(AutoConfigurations.of(BatchJdbcAutoConfiguration.class,
                                BatchAutoConfiguration.class, BatchJobLauncherAutoConfiguration.class))
                        .withBean(DataSource.class, () -> dataSource)
                        .withBean(org.springframework.transaction.PlatformTransactionManager.class,
                                () -> new JdbcTransactionManager(dataSource))
                        .withBean(tools.jackson.databind.ObjectMapper.class, tools.jackson.databind.ObjectMapper::new)
                        .withBean(ProcessRecurringSettlementUseCase.class,
                                () -> mock(ProcessRecurringSettlementUseCase.class))
                        .withBean(RecordRecurringSettlementFailureUseCase.class,
                                () -> mock(RecordRecurringSettlementFailureUseCase.class))
                        .withBean(DispatchRecurringSettlementsUseCase.class,
                                () -> mock(DispatchRecurringSettlementsUseCase.class))
                        .withBean(com.amateur.howmuchbackend.application.port.in.settlement.netting
                                        .ProcessNettingExecutionUseCase.class,
                                () -> mock(com.amateur.howmuchbackend.application.port.in.settlement.netting
                                        .ProcessNettingExecutionUseCase.class))
                        .withUserConfiguration(
                                com.amateur.howmuchbackend.infra.settlement.netting.NettingBatchConfiguration.class,
                                com.amateur.howmuchbackend.infra.settlement.recurring
                                        .RecurringSettlementBatchConfiguration.class)
                        .withPropertyValues("spring.batch.job.enabled=false", "spring.batch.jdbc.initialize-schema=never",
                                "howmuch.settlement.netting.enabled=" + netting,
                                "howmuch.settlement.recurring.enabled=" + repeating,
                                "howmuch.settlement.recurring.poll-enabled=false")
                        .run(context -> {
                            assertThat(context).hasNotFailed().hasSingleBean(JobRepository.class)
                                    .hasSingleBean(org.springframework.batch.core.launch.JobOperator.class)
                                    .doesNotHaveBean(
                                            org.springframework.boot.batch.autoconfigure.JobLauncherApplicationRunner.class);
                            assertThat(context.containsBean("groupNettingJob")).isEqualTo(netting);
                            assertThat(context.containsBean("recurringSettlementRequestJob")).isEqualTo(repeating);
                        });
            }
        }
    }

    @Test
    void uncategorizedSelection_storesUncategorizedSourceAndPublishesOccurrence() {
        var fixture = fixture();
        var registered = create.create(fixture.group(),
                command(fixture.source(), "UNCATEGORIZED", FIRST.toLocalDate()));
        assertThat(registered.categoryCode()).isEqualTo("UNCATEGORIZED");

        clock.at(FIRST.plusMinutes(1));
        assertThat(worker.process(registered.id(), FIRST))
                .isEqualTo(RecurringSettlementExecutionResult.PUBLISHED);
        var occurrence = occurrences.findBySchedule(registered.id(), FIRST).orElseThrow();
        var category = jdbc.queryForMap(
                "SELECT category_code, source, status FROM settlement_categories WHERE settlement_id=?",
                occurrence.settlementId());
        assertThat(category.get("category_code")).isEqualTo("UNCATEGORIZED");
        assertThat(category.get("source")).isEqualTo("UNCATEGORIZED");
        assertThat(category.get("status")).isEqualTo("CLASSIFIED");
        assertThat(recurring.findById(registered.id()).orElseThrow().nextRunAt())
                .isEqualTo(LocalDateTime.of(2026, 11, 30, 0, 0));
    }

    @Test
    void identicalReplayAfterFirstRunElapsed_returnsOriginalIdWithoutMutation() {
        var fixture = fixture();
        var registered = register(fixture);
        var storedNextRun = recurring.findById(registered.id()).orElseThrow().nextRunAt();

        clock.at(LocalDateTime.of(2026, 12, 5, 0, 0));
        // Omitted firstRunDate replays without resolving a shifted future schedule.
        assertThat(create.create(fixture.group(),
                command(fixture.source(), "HOUSING_TELECOM", null)).id()).isEqualTo(registered.id());
        // Identical explicit firstRunDate that is now in the past still replays.
        assertThat(create.create(fixture.group(),
                command(fixture.source(), "HOUSING_TELECOM", FIRST.toLocalDate())).id()).isEqualTo(registered.id());
        // Different category, different group, and different explicit date conflict.
        assertThatThrownBy(() -> create.create(fixture.group(),
                command(fixture.source(), "SHOPPING", FIRST.toLocalDate()))).isInstanceOf(ApplicationException.class);
        assertThatThrownBy(() -> create.create(fixture.group() + 1,
                command(fixture.source(), "HOUSING_TELECOM", null))).isInstanceOf(ApplicationException.class);
        assertThatThrownBy(() -> create.create(fixture.group(),
                command(fixture.source(), "HOUSING_TELECOM", LocalDate.of(2026, 11, 30))))
                .isInstanceOf(ApplicationException.class);

        var after = recurring.findById(registered.id()).orElseThrow();
        assertThat(after.nextRunAt()).isEqualTo(storedNextRun);
        assertThat(after.categoryCode()).isEqualTo("HOUSING_TELECOM");
        assertThat(count("recurring_settlements", "source_settlement_id", fixture.source())).isEqualTo(1);
    }

    @Test
    void listScopesStatusAndHome_arePrivateAndRespectParticipation() {
        var fixture = fixture();
        var registered = register(fixture);

        var creatorParticipating = list.get(fixture.group(), page(RecurringSettlementScope.PARTICIPATING,
                RecurringSettlementStatusFilter.ACTIVE, null));
        assertThat(creatorParticipating.items()).hasSize(1);
        assertThat(creatorParticipating.items().getFirst().myAmount()).isEqualTo(12_000L);
        assertThat(creatorParticipating.items().getFirst().isParticipant()).isTrue();
        assertThat(list.get(fixture.group(), page(RecurringSettlementScope.OTHER,
                RecurringSettlementStatusFilter.ACTIVE, null)).items()).isEmpty();

        // An active group member who is not a participant sees the item only under OTHER and never in home.
        when(actor.getCurrentActor()).thenReturn(new CurrentActor(fixture.observer()));
        var observerOther = list.get(fixture.group(), page(RecurringSettlementScope.OTHER,
                RecurringSettlementStatusFilter.ACTIVE, null));
        assertThat(observerOther.items()).hasSize(1);
        assertThat(observerOther.items().getFirst().myAmount()).isNull();
        assertThat(observerOther.items().getFirst().isParticipant()).isFalse();
        assertThat(list.get(fixture.group(), page(RecurringSettlementScope.PARTICIPATING,
                RecurringSettlementStatusFilter.ACTIVE, null)).items()).isEmpty();
        assertThat(home.getHome(5)).isEmpty();

        when(actor.getCurrentActor()).thenReturn(new CurrentActor(fixture.creator()));
        var creatorHome = home.getHome(5);
        assertThat(creatorHome).hasSize(1);
        assertThat(creatorHome.getFirst().groupId()).isEqualTo(fixture.group());
        assertThat(creatorHome.getFirst().myAmount()).isEqualTo(12_000L);
        assertThat(creatorHome.getFirst().isParticipant()).isTrue();
        assertThat(creatorHome.getFirst().daysUntilNextRun()).isEqualTo(18L);
        assertThat(creatorHome.getFirst().displayState()).isEqualTo(RecurringSettlementDisplayState.SCHEDULED);
        assertThat(creatorHome.getFirst().categoryCode()).isEqualTo("HOUSING_TELECOM");

        cancel.cancel(fixture.group(), registered.id());
        assertThat(list.get(fixture.group(), page(RecurringSettlementScope.ALL,
                RecurringSettlementStatusFilter.ACTIVE, null)).items()).isEmpty();
        var cancelled = list.get(fixture.group(), page(RecurringSettlementScope.ALL,
                RecurringSettlementStatusFilter.CANCELLED, null));
        assertThat(cancelled.items()).hasSize(1);
        assertThat(cancelled.items().getFirst().displayState())
                .isEqualTo(RecurringSettlementDisplayState.CANCELLED);
        assertThat(home.getHome(5)).isEmpty();
    }

    @Test
    void cursors_mustBelongToTemplateAndGroupFilter() {
        var fixture = fixture();
        var first = register(fixture);
        var second = create.create(fixture.group(), command(newDraft(fixture)));

        assertThatThrownBy(() -> list.get(fixture.group(), page(RecurringSettlementScope.ALL,
                RecurringSettlementStatusFilter.ACTIVE, UUID.randomUUID()))).isInstanceOf(ApplicationException.class);
        assertThat(list.get(fixture.group(), page(RecurringSettlementScope.ALL,
                RecurringSettlementStatusFilter.ACTIVE, first.id())).items()).isNotNull();
        assertThatThrownBy(() -> list.get(fixture.group(), page(RecurringSettlementScope.ALL,
                RecurringSettlementStatusFilter.CANCELLED, first.id()))).isInstanceOf(ApplicationException.class);

        clock.at(FIRST.plusMinutes(1));
        worker.process(first.id(), FIRST);
        var firstOccurrence = occurrences.findBySchedule(first.id(), FIRST).orElseThrow();
        assertThatThrownBy(() -> history.get(fixture.group(), second.id(),
                new RecurringSettlementPageCommand(firstOccurrence.id(), 20, null, null)))
                .isInstanceOf(ApplicationException.class);
        assertThatThrownBy(() -> history.get(fixture.group(), first.id(),
                new RecurringSettlementPageCommand(UUID.randomUUID(), 20, null, null)))
                .isInstanceOf(ApplicationException.class);
        assertThat(history.get(fixture.group(), first.id(),
                new RecurringSettlementPageCommand(firstOccurrence.id(), 20, null, null)).items()).isNotNull();
    }

    @Test
    void dueQuery_excludesManualReviewAndLaunchesFreshAndRetryWork() {
        var fixture = fixture();
        var fresh = register(fixture);
        var poison = create.create(fixture.group(), command(newDraft(fixture)));
        var retry = create.create(fixture.group(), command(newDraft(fixture)));

        jdbc.update("UPDATE recurring_settlements SET failure_count=1,"
                        + " last_failure_code='RECURRING_UNEXPECTED_EXECUTION_FAILURE', last_failure_at=?,"
                        + " updated_at=? WHERE id=?", CREATED, CREATED, poison.id());
        jdbc.update("UPDATE recurring_settlements SET failure_count=1,"
                        + " last_failure_code='RECURRING_TRANSIENT_DATABASE_FAILURE', last_failure_at=?,"
                        + " retry_at=?, updated_at=? WHERE id=?", CREATED, FIRST, CREATED, retry.id());

        clock.at(FIRST.plusMinutes(1));
        assertThat(dispatcher.dispatch(50)).isEqualTo(2);
        assertThat(count("recurring_settlement_occurrences", "recurring_settlement_id", fresh.id())).isEqualTo(1);
        assertThat(count("recurring_settlement_occurrences", "recurring_settlement_id", retry.id())).isEqualTo(1);
        assertThat(count("recurring_settlement_occurrences", "recurring_settlement_id", poison.id())).isZero();
        var poisonAfter = recurring.findById(poison.id()).orElseThrow();
        assertThat(poisonAfter.failureCount()).isEqualTo(1);
        assertThat(poisonAfter.lastFailureCode()).isEqualTo(RecurringSettlement.UNEXPECTED_FAILURE);
        assertThat(recurring.findById(retry.id()).orElseThrow().failureCount()).isZero();
    }

    @Test
    void dueQuery_horizonExcludesPostHorizonRowsWhileRetriesUseCurrentTime() {
        var fixture = fixture();
        var horizonFresh = register(fixture);
        var retry = create.create(fixture.group(), command(newDraft(fixture)));
        var postHorizon = create.create(fixture.group(), command(newDraft(fixture)));

        jdbc.update("UPDATE recurring_settlements SET next_run_at=?, failure_count=1,"
                        + " last_failure_code='RECURRING_TRANSIENT_DATABASE_FAILURE', last_failure_at=?,"
                        + " retry_at=?, updated_at=? WHERE id=?",
                FIRST.minusMinutes(2), CREATED, FIRST.plusMinutes(5), CREATED, retry.id());
        jdbc.update("UPDATE recurring_settlements SET next_run_at=?, updated_at=? WHERE id=?",
                FIRST.plusMinutes(10), CREATED, postHorizon.id());

        // Horizon at FIRST: the fresh row and the now-eligible retry are within it; the row scheduled after
        // the horizon is excluded even though it is already <= now.
        var due = recurring.findDue(FIRST.plusMinutes(20), FIRST, null, 10).stream()
                .map(LoadRecurringSettlementPort.DueOccurrence::id).toList();
        assertThat(due).contains(horizonFresh.id(), retry.id()).doesNotContain(postHorizon.id());

        // The retry's eligibility still uses the current time: before retryAt it is excluded.
        var beforeRetry = recurring.findDue(FIRST, FIRST, null, 10).stream()
                .map(LoadRecurringSettlementPort.DueOccurrence::id).toList();
        assertThat(beforeRetry).contains(horizonFresh.id()).doesNotContain(retry.id(), postHorizon.id());
    }

    @Test
    void directForm_updateAfterPublishing_thenDelete_preservesBothOccurrenceSnapshots() {
        var f = fixture();
        int beforeDrafts = jdbc.queryForObject("SELECT count(*) FROM settlements", Integer.class);
        var value = createForm.create(f.group(), form(f, 20001, List.of(f.creator(), f.member())));
        assertThat(recurring.findById(value.id()).orElseThrow().sourceSettlementId()).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM settlements", Integer.class)).isEqualTo(beforeDrafts);
        assertThat(value.participants()).extracting(RecurringSettlement.Participant::amount).containsExactly(10001L, 10000L);
        clock.at(FIRST.plusMinutes(1));
        worker.process(value.id(), FIRST);
        var first = occurrences.findBySchedule(value.id(), FIRST).orElseThrow();
        var changed = updateForm.update(f.group(), value.id(), form(f, 30003, List.of(f.observer(), f.creator(), f.member())));
        assertThat(changed.id()).isEqualTo(value.id());
        assertThat(changed.createdAt()).isEqualTo(value.createdAt());
        assertThat(changed.nextRunAt()).isEqualTo(LocalDateTime.of(2026, 11, 30, 0, 0));
        assertThat(jdbc.queryForObject("SELECT total_amount FROM settlements WHERE id=?", Long.class, first.settlementId())).isEqualTo(20001L);
        clock.at(changed.nextRunAt().plusMinutes(1));
        worker.process(value.id(), changed.nextRunAt());
        var second = occurrences.findBySchedule(value.id(), changed.nextRunAt()).orElseThrow();
        assertThat(jdbc.queryForObject("SELECT total_amount FROM settlements WHERE id=?", Long.class, second.settlementId())).isEqualTo(30003L);
        assertThat(jdbc.queryForList("SELECT amount FROM settlement_shares WHERE settlement_id=? ORDER BY amount", Long.class, second.settlementId())).containsExactly(10001L, 10001L, 10001L);
        deleteForm.delete(f.group(), value.id());
        deleteForm.delete(f.group(), value.id());
        var deleted = recurring.findById(value.id()).orElseThrow();
        assertThat(deleted.deletedAt()).isNotNull();
        assertThat(deleted.status()).isEqualTo(RecurringSettlement.Status.CANCELLED);
        assertThat(list.get(f.group(), page(RecurringSettlementScope.ALL, RecurringSettlementStatusFilter.CANCELLED, null)).items()).isEmpty();
        assertThatThrownBy(() -> get.get(f.group(), value.id())).isInstanceOf(ApplicationException.class);
        assertThatThrownBy(() -> history.get(f.group(), value.id(), page(RecurringSettlementScope.ALL, RecurringSettlementStatusFilter.ACTIVE, null))).isInstanceOf(ApplicationException.class);
        assertThat(count("recurring_settlement_occurrences", "recurring_settlement_id", value.id())).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM settlements WHERE id IN (?,?)", Integer.class, first.settlementId(), second.settlementId())).isEqualTo(2);
        assertThat(dispatcher.dispatch(50)).isZero();
    }

    @Test
    void formApi_validatesExclusiveShapes_andSupportsPutDelete() throws Exception {
        var f = fixture();
        String path = "/api/v2/groups/" + f.group() + "/recurring-settlements";
        String body = formJson(f, "form HTTP", 20001);
        mvc.perform(post(path).with(user("signed")).contentType("application/json").content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("data.title").value("form HTTP"))
                .andExpect(jsonPath("data.participants[0].amount").value(10001));
        UUID id = jdbc.queryForObject("SELECT id FROM recurring_settlements WHERE group_id=?", UUID.class, f.group());
        mvc.perform(MockMvcRequestBuilders.put(path + "/" + id)
                        .with(user("signed")).contentType("application/json").content(formJson(f, "edited HTTP", 30000)))
                .andExpect(status().isOk()).andExpect(jsonPath("data.id").value(id.toString()))
                .andExpect(jsonPath("data.title").value("edited HTTP"));
        for (String bad : List.of(body.replaceFirst("\\{", "{\"frequency\":\"MONTHLY\","),
                body.replace("\"title\":", "\"sourceSettlementId\":\"" + f.source() + "\",\"title\":"),
                body.replace("\"receivingAccountId\":" + f.account(), "\"receivingAccountId\":null"),
                body.replace("[" + f.creator() + "," + f.member() + "]", "[" + f.creator() + ",null]"))) {
            mvc.perform(post(path).with(user("signed")).contentType("application/json").content(bad)).andExpect(status().isBadRequest());
        }
        mvc.perform(MockMvcRequestBuilders.put(path + "/" + id)
                        .contentType("application/json").content(body)).andExpect(status().isUnauthorized());
        when(actor.getCurrentActor()).thenReturn(new CurrentActor(f.member()));
        mvc.perform(MockMvcRequestBuilders.delete(path + "/" + id).with(user("signed")))
                .andExpect(status().isForbidden());
        mvc.perform(MockMvcRequestBuilders.put(path + "/" + id)
                        .with(user("signed")).contentType("application/json").content(body)).andExpect(status().isForbidden());
        when(actor.getCurrentActor()).thenReturn(new CurrentActor(f.creator()));
        mvc.perform(MockMvcRequestBuilders.delete(path + "/" + id).with(user("signed"))).andExpect(status().isOk());
        mvc.perform(MockMvcRequestBuilders.delete(path + "/" + id).with(user("signed"))).andExpect(status().isOk());
        mvc.perform(get(path + "/" + id).with(user("signed"))).andExpect(status().isNotFound());
    }

    @Test
    void invalidFormMembershipAllocationsAndAccount_doNotPersistAnything() {
        var f = fixture();
        for (var ids : List.of(List.of(f.creator(), f.creator()), List.of(f.member(), f.observer()), List.of(f.creator(), 999999999L))) {
            assertThatThrownBy(() -> createForm.create(f.group(), form(f, 20000, ids))).isInstanceOf(ApplicationException.class);
        }
        assertThatThrownBy(() -> createForm.create(f.group(), form(f, 1, List.of(f.creator(), f.member())))).isInstanceOf(ApplicationException.class);
        var other = fixture();
        when(actor.getCurrentActor()).thenReturn(new CurrentActor(f.creator()));
        var input = form(f, 20000, List.of(f.creator(), f.member()));
        var wrongAccount = new WriteRecurringSettlementCommand(
                input.title(), input.amount(), input.categoryCode(), input.frequency(), input.dayOfWeek(), input.dayOfMonth(),
                null, input.participantUserIds(), other.account());
        assertThatThrownBy(() -> createForm.create(f.group(), wrongAccount)).isInstanceOf(ApplicationException.class);
        jdbc.update("UPDATE group_members SET status='LEFT',left_at=? WHERE group_id=? AND user_id=?", CREATED, f.group(), f.member());
        assertThatThrownBy(() -> createForm.create(f.group(), input)).isInstanceOf(ApplicationException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM recurring_settlements WHERE group_id=?", Integer.class, f.group())).isZero();
    }

    @Test
    void dueFailedCancelledAndWrongGroupEdits_areRejected_withoutSkippingOccurrences() {
        var f = fixture(); var value = register(f);
        var input = form(f, 30000, List.of(f.creator(), f.member()));
        assertThatThrownBy(() -> updateForm.update(f.group() + 99999, value.id(), input)).isInstanceOf(ApplicationException.class);
        clock.at(FIRST);
        assertThatThrownBy(() -> updateForm.update(f.group(), value.id(), input)).isInstanceOf(ApplicationException.class);
        clock.at(CREATED);
        jdbc.update("UPDATE recurring_settlements SET last_failure_code='RECURRING_MANUAL_EXECUTION_FAILURE',last_failure_at=? WHERE id=?", CREATED, value.id());
        assertThatThrownBy(() -> updateForm.update(f.group(), value.id(), input)).isInstanceOf(ApplicationException.class);
        cancel.cancel(f.group(), value.id());
        assertThatThrownBy(() -> updateForm.update(f.group(), value.id(), input)).isInstanceOf(ApplicationException.class);
        clock.at(CREATED.plusDays(1)); deleteForm.delete(f.group(), value.id());
        assertThat(recurring.findById(value.id()).orElseThrow().cancelledAt()).isEqualTo(CREATED);
        assertThatThrownBy(() -> create.create(f.group(), command(f.source()))).isInstanceOf(ApplicationException.class);
    }

    @Test
    void cadenceChange_recomputesFutureSchedule_andReplacesParticipantsAtomically() {
        var f = fixture();var value = register(f);
        var input = new WriteRecurringSettlementCommand(
                "매주 수정", 31001, "SHOPPING", RecurrenceSchedule.Frequency.WEEKLY, DayOfWeek.FRIDAY, null,
                null, List.of(f.observer(), f.creator()), f.account());
        var changed = updateForm.update(f.group(), value.id(), input);
        assertThat(changed.schedule().frequency()).isEqualTo(RecurrenceSchedule.Frequency.WEEKLY);
        assertThat(changed.nextRunAt()).isEqualTo(LocalDateTime.of(2026, 10, 16, 0, 0));
        assertThat(changed.participants()).extracting(RecurringSettlement.Participant::userId).containsExactly(f.observer(), f.creator());
        assertThat(changed.participants()).extracting(RecurringSettlement.Participant::amount).containsExactly(15500L, 15501L);
        assertThat(changed.expenses()).singleElement().satisfies(cost -> assertThat(cost.description()).isEqualTo("매주 수정"));
        assertThat(recurring.findById(value.id()).orElseThrow().sourceSettlementId()).isEqualTo(f.source());
    }

    @Test
    void deletedCursorAndOtherCreator_areNotAllowed() {
        var f=fixture();var value=register(f);cancel.cancel(f.group(),value.id());deleteForm.delete(f.group(),value.id());
        assertThatThrownBy(() -> list.get(f.group(), page(RecurringSettlementScope.ALL, RecurringSettlementStatusFilter.CANCELLED, value.id()))).isInstanceOf(ApplicationException.class);
        when(actor.getCurrentActor()).thenReturn(new CurrentActor(f.member()));
        assertThatThrownBy(() -> deleteForm.delete(f.group(),value.id())).isInstanceOf(ApplicationException.class);
    }

    @Test
    void workerRejectsSnapshotChangedWhileAcquiringLocks_andClassifiesItForRetry() {
        var f=fixture();var value=register(f);clock.at(FIRST.plusMinutes(1));
        var original=recurring.findById(value.id()).orElseThrow();
        var alternate=original.revise("edited", "SHOPPING", 30000, f.account(), original.schedule(),
                List.of(new RecurringSettlement.Participant(f.creator(),15000,0),new RecurringSettlement.Participant(f.observer(),15000,1)),CREATED);
        Mockito.doReturn(Optional.of(alternate)).when(persistenceTarget()).findByIdForUpdate(value.id());
        assertThatThrownBy(() -> worker.process(value.id(), FIRST))
                .isInstanceOf(RecurringSettlementSnapshotChangedException.class)
                .satisfies(error -> assertThat(RecurringSettlementFailureClassifier.transientDatabaseFailure(error)).isTrue());
        assertThat(count("recurring_settlement_occurrences","recurring_settlement_id",value.id())).isZero();
    }

    private WriteRecurringSettlementCommand form(Fixture f, long amount, List<Long> people) {
        return new WriteRecurringSettlementCommand(
                "직접 월세", amount, "HOUSING_TELECOM", RecurrenceSchedule.Frequency.MONTHLY, null,31,null,people,f.account());
    }

    private String formJson(Fixture f, String title, long amount) {
        return """
                {"title":"%s","amount":%d,"categoryCode":"HOUSING_TELECOM",
                 "cadence":{"frequency":"MONTHLY","dayOfMonth":31},"firstRunDate":"2026-10-31",
                 "participantUserIds":[%d,%d],"receivingAccountId":%d}
                """.formatted(title,amount,f.creator(),f.member(),f.account());
    }

    private RecurringSettlementPageCommand page(RecurringSettlementScope scope,
            RecurringSettlementStatusFilter status, UUID cursor) {
        return new RecurringSettlementPageCommand(cursor, 20, scope, status);
    }

    private void failAfterRequestWasWritten() {
        doAnswer(invocation -> {
            RecurringSettlement value = invocation.getArgument(0);
            if (value.nextRunAt() != null && value.nextRunAt().isAfter(FIRST)) {
                throw new CannotAcquireLockException(
                        "synthetic transient error; raw cause must not enter Batch metadata");
            }
            return invocation.callRealMethod();
        }).when(persistenceTarget()).update(any());
    }

    private RecurringSettlementPersistenceAdapter persistenceTarget() {
        return AopTestUtils.getUltimateTargetObject(persistence);
    }

    private JobExecution staleExecution(UUID id) {
        var parameters = new JobParametersBuilder().addString("recurringSettlementId", id.toString())
                .addString("scheduledFor", FIRST.toInstant(ZoneOffset.UTC).toString()).toJobParameters();
        var instance = repository.createJobInstance("recurringSettlementRequestJob", parameters);
        var execution = repository.createJobExecution(instance, parameters, new ExecutionContext());
        execution.setStatus(BatchStatus.STARTED);
        repository.update(execution);
        return execution;
    }

    private RecurringSettlementResult register(Fixture fixture) {
        return create.create(fixture.group(), command(fixture.source()));
    }

    private CreateRecurringSettlementCommand command(UUID source) {
        return new CreateRecurringSettlementCommand(source, RecurrenceSchedule.Frequency.MONTHLY, null, 31,
                FIRST.toLocalDate(), "HOUSING_TELECOM");
    }

    private CreateRecurringSettlementCommand command(UUID source, String categoryCode, LocalDate firstRunDate) {
        return new CreateRecurringSettlementCommand(source, RecurrenceSchedule.Frequency.MONTHLY, null, 31,
                firstRunDate, categoryCode);
    }

    private Fixture fixture() {
        long creator = userId("생성자");
        long member = userId("참여자");
        long observer = userId("관찰자");
        long account = account(creator);
        long group = jdbc.queryForObject("""
                INSERT INTO groups(name,owner_id,invite_code,status,creator_id,created_at,updated_at)
                VALUES('recurring',?,?,'ACTIVE',?,?,?) RETURNING id
                """, Long.class, creator,
                UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase(Locale.ROOT),
                creator, CREATED, CREATED);
        for (long person : new long[]{creator, member, observer}) {
            jdbc.update("""
                    INSERT INTO group_members(group_id,user_id,role,status,joined_at,created_at,updated_at)
                    VALUES(?,?,?,'ACTIVE',?,?,?)
                    """, group, person, person == creator ? "OWNER" : "MEMBER", CREATED, CREATED, CREATED);
        }
        UUID source = insertDraft(group, creator, member, account);
        when(actor.getCurrentActor()).thenReturn(new CurrentActor(creator));
        return new Fixture(group, creator, member, observer, account, source);
    }

    private UUID newDraft(Fixture fixture) {
        return insertDraft(fixture.group(), fixture.creator(), fixture.member(), fixture.account());
    }

    private UUID insertDraft(long group, long creator, long member, long account) {
        UUID source = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO settlements(id,creator_id,title,total_amount,status,allocation_type,receiving_account_id,created_at,updated_at)
                VALUES(?,?,'반복 월세',20000,'DRAFT','DUTCH_PAY',?,?,?)
                """, source, creator, account, CREATED, CREATED);
        int order = 0;
        for (long person : new long[]{creator, member}) {
            UUID participant = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO settlement_participants(id,settlement_id,source,linked_user_id,source_group_id,display_name,display_order,created_at,updated_at)
                    VALUES(?,?,?,?,?,'fixture',?,?,?)
                    """, participant, source, person == creator ? "REGISTERED_USER" : "GROUP_MEMBER", person,
                    person == creator ? null : group, order++, CREATED, CREATED);
            jdbc.update("""
                    INSERT INTO settlement_dutch_pay_allocations(id,settlement_id,participant_id,amount,created_at,updated_at)
                    VALUES(?,?,?,?,?,?)
                    """, UUID.randomUUID(), source, participant, person == creator ? 12_000 : 8_000, CREATED, CREATED);
        }
        jdbc.update("""
                INSERT INTO settlement_expenses(id,settlement_id,source,description,amount,display_order,created_at,updated_at)
                VALUES(?,?,'MANUAL','월세',20000,0,?,?)
                """, UUID.randomUUID(), source, CREATED, CREATED);
        return source;
    }

    private long userId(String name) {
        String unique = UUID.randomUUID().toString().replace("-", "");
        return jdbc.queryForObject("""
                INSERT INTO users(identity_key,name,public_id,phone_number,pin_hash,phone_verified,role,active,required_terms_agreed_at,created_at,updated_at)
                VALUES(?,?,?,?,'unused',true,'USER',true,?,?,?) RETURNING id
                """, Long.class, unique, name, "r" + unique.substring(0, 15), "test-" + unique.substring(0, 12),
                CREATED, CREATED, CREATED);
    }

    private long account(long creator) {
        long connection = jdbc.queryForObject("""
                INSERT INTO external_connections(user_id,connected_id_ciphertext,configuration_fingerprint,active,created_at,updated_at)
                VALUES(?,?,?,true,?,?) RETURNING id
                """, Long.class, creator, cipher.encryptConnectedId(creator, "test-connected-" + creator),
                fingerprint.current(), CREATED, CREATED);
        jdbc.update("INSERT INTO connected_institutions(external_connection_id,organization,status,created_at,updated_at)"
                        + " VALUES(?,'0088','CONNECTED',?,?)", connection, CREATED, CREATED);
        return jdbc.queryForObject("""
                INSERT INTO accounts(user_id,external_connection_id,organization,candidate_key,external_account_key_ciphertext,type,name,masked_number,balance,currency,active,created_at,updated_at,is_default,display_order,product_code)
                VALUES(?,?,'0088',?,?,'DEPOSIT_TRUST','test account','****1234',100000,'KRW',true,?,?,true,0,'11') RETURNING id
                """, Long.class, creator, connection, UUID.randomUUID().toString().replace("-", "") + "a".repeat(32),
                cipher.encryptAccountKey(creator, connection, "100200" + creator), CREATED, CREATED);
    }

    private int count(String table, String column, Object value) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE " + column + "=?", Integer.class,
                value);
    }

    private int newSettlementCount(Fixture fixture) {
        return jdbc.queryForObject("SELECT count(*) FROM settlements WHERE creator_id=? AND id<>?", Integer.class,
                fixture.creator(), fixture.source());
    }

    private Object attempt(Callable<?> action) {
        try {
            return action.call();
        } catch (ApplicationException expected) {
            return expected.getStatusCode().getCode();
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private List<Object> concurrent(Callable<?> first, Callable<?> second) throws Exception {
        try (var pool = Executors.newFixedThreadPool(2)) {
            var gate = new CountDownLatch(1);
            Future<?> a = pool.submit(() -> {
                gate.await();
                return first.call();
            });
            Future<?> b = pool.submit(() -> {
                gate.await();
                return second.call();
            });
            gate.countDown();
            return List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS));
        }
    }

    private record Fixture(long group, long creator, long member, long observer, long account, UUID source) {
    }
}
