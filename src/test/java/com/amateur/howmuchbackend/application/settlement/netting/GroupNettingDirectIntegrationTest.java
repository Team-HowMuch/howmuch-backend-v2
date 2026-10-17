package com.amateur.howmuchbackend.application.settlement.netting;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.amateur.howmuchbackend.adapter.out.persistence.external.CodefSecretCipher;
import com.amateur.howmuchbackend.application.account.catalog.BankCatalog;
import com.amateur.howmuchbackend.application.auth.model.CurrentActor;
import com.amateur.howmuchbackend.application.port.in.settlement.netting.*;
import com.amateur.howmuchbackend.application.port.in.settlement.netting.command.*;
import com.amateur.howmuchbackend.application.port.in.settlement.netting.result.*;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.application.port.out.external.BankTransactionPort;
import com.amateur.howmuchbackend.application.port.out.settlement.netting.*;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.common.exception.StatusCode;
import com.amateur.howmuchbackend.common.exception.status.CommonStatusCode;
import com.amateur.howmuchbackend.domain.external.status.ExternalConnectionStatusCode;
import com.amateur.howmuchbackend.domain.group.status.GroupStatusCode;
import com.amateur.howmuchbackend.domain.settlement.netting.NettingStatusCode;
import com.amateur.howmuchbackend.infra.codef.config.CodefConfigurationFingerprint;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.*;
import java.util.*;

@SpringBootTest(
        useMainMethod = SpringBootTest.UseMainMethod.ALWAYS,
        properties = {
            "howmuch.settlement.netting.enabled=true",
            "howmuch.settlement.notification-outbox.enabled=false",
            "howmuch.settlement.netting.dispatch-delay-ms=3600000",
            "howmuch.codef.connected-id-encryption-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="
        })
@org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
class GroupNettingDirectIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired CodefSecretCipher cipher;
    @Autowired CodefConfigurationFingerprint fingerprint;
    @Autowired ExecuteGroupNettingUseCase executions;
    @Autowired DispatchNettingExecutionsUseCase dispatcher;
    @Autowired GetGroupNettingsUseCase nettings;
    @Autowired ReconcileNettedTransfersUseCase reconciliation;
    @Autowired GetNettedTransferInstructionUseCase instructions;
    @Autowired ProcessNettingExecutionUseCase worker;
    @Autowired NettingExecutionStorePort store;
    @MockitoBean CurrentActorPort actor;
    @MockitoBean BankTransactionPort bank;
    @MockitoBean BankCatalog catalog;

    @MockitoBean
    com.amateur.howmuchbackend.application.port.out.settlement.SettlementBankLookupBudgetPort
            lookupBudget;

    private final List<Long> fixtureGroups = new ArrayList<>();

    @AfterEach
    void finishOwnPendingFixtures() {
        for (long group : fixtureGroups)
            jdbc.update(
                    "UPDATE group_netting_executions SET"
                        + " status='FAILED',worker_id=NULL,lease_token=NULL,lease_until=NULL,next_attempt_at=NULL,finished_at=now()"
                        + " AT TIME ZONE 'UTC',updated_at=now() AT TIME ZONE 'UTC' WHERE group_id=?"
                        + " AND status IN ('QUEUED','SYNCING','RETRY')",
                    group);
    }

    record Fixture(long group, long a, long b, long accountA, long accountB, UUID ab, UUID ba) {}

    @BeforeEach
    void bank() {
        when(bank.fetch(anyString(), anyString(), anyString(), any(), any(), any()))
                .thenAnswer(
                        inv -> {
                            assertThat(
                                            TransactionSynchronizationManager
                                                    .isActualTransactionActive())
                                    .isFalse();
                            return List.of();
                        });
    }

    void as(long id) {
        when(actor.getCurrentActor()).thenReturn(new CurrentActor(id));
    }

    Fixture fixture(long ab, long ba) {
        long a = user("A"), b = user("B"), aa = account(a), bb = account(b);
        var now = LocalDateTime.now(ZoneOffset.UTC).minusDays(1);
        long group =
                jdbc.queryForObject(
                        "INSERT INTO"
                            + " groups(name,owner_id,invite_code,status,creator_id,created_at,updated_at)"
                            + " VALUES('direct',?,?,'ACTIVE',?,?,?) RETURNING id",
                        Long.class,
                        a,
                        UUID.randomUUID()
                                .toString()
                                .replace("-", "")
                                .substring(0, 8)
                                .toUpperCase(Locale.ROOT),
                        a,
                        now,
                        now);
        fixtureGroups.add(group);
        member(group, a, "OWNER", now);
        member(group, b, "MEMBER", now);
        return new Fixture(
                group,
                a,
                b,
                aa,
                bb,
                original(group, b, a, bb, ab, now),
                original(group, a, b, aa, ba, now));
    }

    GroupNettingResult compress(Fixture f) {
        return compress(f, f.a());
    }

    GroupNettingResult compress(Fixture f, long requester) {
        as(requester);
        var queued =
                executions.execute(f.group(), new ExecuteGroupNettingCommand(UUID.randomUUID()));
        dispatcher.dispatch();
        var e = executions.getExecution(f.group(), queued.executionId());
        assertThat(e.status())
                .as("execution %s error %s", e.executionId(), e.errorCode())
                .isEqualTo("SUCCEEDED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM batch_job_execution_params p JOIN"
                                    + " batch_job_execution e ON"
                                    + " e.job_execution_id=p.job_execution_id WHERE"
                                    + " p.parameter_name='executionId' AND p.parameter_value=? AND"
                                    + " e.status='COMPLETED'",
                                Integer.class,
                                e.executionId().toString()))
                .isEqualTo(1);
        return nettings.get(f.group(), e.nettingId());
    }

    long income(long account, long amount, String reference) {
        var now = LocalDateTime.now(ZoneOffset.UTC);
        return jdbc.queryForObject(
                "INSERT INTO"
                    + " account_transactions(account_id,external_transaction_key,transaction_type,amount,transacted_at,description,after_balance,created_at,updated_at)"
                    + " VALUES(?,?,'INCOME',?,?,?,100000,?,?) RETURNING id",
                Long.class,
                account,
                UUID.randomUUID().toString().replace("-", "") + "a".repeat(32),
                amount,
                now,
                reference,
                now,
                now);
    }

    @Test
    void directExecutionNeedsNoBodyOrConsentAndReturnsSharedResultAndOriginalStatus()
            throws Exception {
        var f = fixture(15000, 10000);
        as(f.a());
        var key = UUID.randomUUID();
        mvc.perform(
                        post("/api/v2/groups/{g}/settlement-nettings", f.group())
                                .with(
                                        org.springframework.security.test.web.servlet.request
                                                .SecurityMockMvcRequestPostProcessors.user("test")
                                                .roles("USER"))
                                .header("Idempotency-Key", key.toString()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.status").value("QUEUED"));
        var again = executions.execute(f.group(), new ExecuteGroupNettingCommand(key));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM group_netting_executions WHERE group_id=?",
                                Integer.class,
                                f.group()))
                .isEqualTo(1);
        UUID participant =
                jdbc.queryForObject(
                        "SELECT id FROM settlement_participants WHERE settlement_id=? AND"
                                + " linked_user_id=?",
                        UUID.class,
                        f.ab(),
                        f.a());
        assertThatThrownBy(() -> originalInstructions.read(f.ab(), participant))
                .isInstanceOfSatisfying(
                        ApplicationException.class,
                        e ->
                                assertThat(e.getStatusCode())
                                        .isEqualTo(NettingStatusCode.NETTING_PROCESSING));
        dispatcher.dispatch();
        var e = executions.getExecution(f.group(), again.executionId());
        assertThat(e.status()).as("error %s", e.errorCode()).isEqualTo("SUCCEEDED");
        var n = nettings.get(f.group(), e.nettingId());
        assertThat(n.transfers())
                .singleElement()
                .satisfies(
                        t -> {
                            assertThat(t.payerId()).isEqualTo(f.a());
                            assertThat(t.receiverId()).isEqualTo(f.b());
                            assertThat(t.amount()).isEqualTo(5000);
                        });
        as(f.b());
        assertThat(nettings.current(f.group())).isEqualTo(n);
        assertThatThrownBy(
                        () ->
                                instructions.getInstruction(
                                        f.group(),
                                        n.nettingId(),
                                        n.transfers().getFirst().transferId()))
                .isInstanceOfSatisfying(
                        ApplicationException.class,
                        ex ->
                                assertThat(ex.getStatusCode())
                                        .isEqualTo(NettingStatusCode.NETTING_ACCESS_DENIED));
        mvc.perform(
                        get("/api/v2/settlements")
                                .param("tab", "RECEIVE")
                                .param("status", "IN_PROGRESS")
                                .param("groupId", Long.toString(f.group()))
                                .with(
                                        org.springframework.security.test.web.servlet.request
                                                .SecurityMockMvcRequestPostProcessors.user("test")
                                                .roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());
        mvc.perform(
                        get("/api/v2/settlements/{id}", f.ab())
                                .with(
                                        org.springframework.security.test.web.servlet.request
                                                .SecurityMockMvcRequestPostProcessors.user("test")
                                                .roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("NETTING_IN_PROGRESS"));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM group_netting_execution_snapshots WHERE"
                                        + " execution_id=?",
                                Integer.class,
                                e.executionId()))
                .isEqualTo(1);
    }

    @Test
    void partialPaymentAndNewDebtRollOverOnlyUnpaidResidualAndPreserveHistory() {
        var f = fixture(15000, 10000);
        var first = compress(f);
        var old = first.transfers().getFirst();
        long tx = income(f.accountB(), 2000, old.reference());
        as(f.b());
        var refresh = reconciliation.reconcile(f.group(), first.nettingId(), UUID.randomUUID());
        dispatcher.dispatch();
        assertThat(executions.getExecution(f.group(), refresh.executionId()).status())
                .isEqualTo("SUCCEEDED");
        assertThat(
                        nettings.get(f.group(), first.nettingId())
                                .transfers()
                                .getFirst()
                                .confirmedAmount())
                .isEqualTo(2000);
        long c = user("C");
        member(f.group(), c, "MEMBER", LocalDateTime.now(ZoneOffset.UTC));
        UUID next =
                original(
                        f.group(), f.a(), c, f.accountA(), 4000, LocalDateTime.now(ZoneOffset.UTC));
        assertThat(settlements.findAllForUser(f.a(), "RECEIVE", "ALL", f.group(), null, null))
                .extracting(row -> row.getSettlementId())
                .containsExactly(next);
        var second = compress(f, f.b());
        assertThat(second.version()).isEqualTo(2);
        assertThat(second.transfers()).hasSize(2);
        var balances = new TreeMap<Long, Long>();
        for (var transfer : second.transfers()) {
            balances.merge(transfer.payerId(), -transfer.amount(), Math::addExact);
            balances.merge(transfer.receiverId(), transfer.amount(), Math::addExact);
        }
        balances.values().removeIf(value -> value == 0);
        assertThat(balances)
                .containsExactlyInAnyOrderEntriesOf(Map.of(f.a(), 1000L, f.b(), 3000L, c, -4000L));
        assertThat(nettings.get(f.group(), first.nettingId()).status()).isEqualTo("SUPERSEDED");
        assertThat(
                        nettings.get(f.group(), first.nettingId())
                                .transfers()
                                .getFirst()
                                .confirmedAmount())
                .isEqualTo(2000);
        assertThat(second.sources()).hasSize(3);
        assertOriginalsHidden(f);
        assertThat(second.sources())
                .extracting(GroupNettingResult.Source::settlementId)
                .containsExactlyInAnyOrder(f.ab(), f.ba(), next);
        assertThat(second.sources())
                .allSatisfy(
                        source -> {
                            assertThat(source.title()).isEqualTo("original");
                            assertThat(source.status()).isEqualTo("NETTING_IN_PROGRESS");
                            assertThat(source.requesterName()).isNotBlank();
                            assertThat(source.requestedAt()).isNotNull();
                        });
        assertThat(
                        jdbc.queryForObject(
                                "SELECT carried_amount FROM group_netting_rollover_transfers WHERE"
                                        + " successor_netting_id=?",
                                Long.class,
                                second.nettingId()))
                .isEqualTo(3000);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM settlement_bank_transaction_claims WHERE"
                                        + " account_transaction_id=?",
                                Integer.class,
                                tx))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM settlements WHERE id=?", String.class, next))
                .isEqualTo("NETTING_IN_PROGRESS");
        as(f.a());
        assertThatThrownBy(
                        () ->
                                instructions.getInstruction(
                                        f.group(), first.nettingId(), old.transferId()))
                .isInstanceOfSatisfying(
                        ApplicationException.class,
                        e ->
                                assertThat(e.getStatusCode())
                                        .isEqualTo(NettingStatusCode.NETTING_TRANSFER_REPLACED));
        assertThat(nettings.getBySource(f.ab()).nettingId()).isEqualTo(second.nettingId());
    }

    @ParameterizedTest
    @ValueSource(strings = {"OWNER", "MEMBER"})
    void zeroNetBalancesCompleteWithoutFakeDeposits(String role) {
        var f = fixture(10000, 10000);
        var n = compress(f, role.equals("OWNER") ? f.a() : f.b());
        assertThat(n.transfers()).isEmpty();
        assertThat(n.status()).isEqualTo("COMPLETED");
        assertOriginalsHidden(f);
        assertThat(n.sources())
                .allSatisfy(source -> assertThat(source.status()).isEqualTo("COMPLETED"));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM settlements WHERE id=?", String.class, f.ab()))
                .isEqualTo("COMPLETED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM settlement_deposits WHERE settlement_id IN"
                                        + " (?,?)",
                                Integer.class,
                                f.ab(),
                                f.ba()))
                .isZero();
    }

    @Test
    void memberCanExecuteThroughHttpAndBatchWithoutReceivingOtherActorsPrivateData()
            throws Exception {
        var f = fixture(15000, 10000);
        as(f.b());
        var key = UUID.randomUUID();
        mvc.perform(
                        post("/api/v2/groups/{g}/settlement-nettings", f.group())
                                .with(
                                        org.springframework.security.test.web.servlet.request
                                                .SecurityMockMvcRequestPostProcessors.user("test")
                                                .roles("USER"))
                                .header("Idempotency-Key", key.toString()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.status").value("QUEUED"));
        var queued = executions.execute(f.group(), new ExecuteGroupNettingCommand(key));
        dispatcher.dispatch();
        var result = executions.getExecution(f.group(), queued.executionId());
        assertThat(result.status()).as("error %s", result.errorCode()).isEqualTo("SUCCEEDED");
        assertThat(store.find(f.group(), queued.executionId()).orElseThrow().actorId())
                .isEqualTo(f.b());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT owner_id FROM group_netting_execution_snapshots WHERE"
                                        + " execution_id=?",
                                Long.class,
                                queued.executionId()))
                .isEqualTo(f.a());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM batch_job_execution_params p JOIN"
                                    + " batch_job_execution e ON"
                                    + " e.job_execution_id=p.job_execution_id WHERE"
                                    + " p.parameter_name='executionId' AND p.parameter_value=? AND"
                                    + " e.status='COMPLETED'",
                                Integer.class,
                                queued.executionId().toString()))
                .isEqualTo(1);
        var n = nettings.get(f.group(), result.nettingId());
        var transfer = n.transfers().getFirst();
        assertThat(transfer.payerId()).isEqualTo(f.a());
        assertThat(transfer.amount()).isEqualTo(5000);
        assertThatThrownBy(
                        () ->
                                instructions.getInstruction(
                                        f.group(), n.nettingId(), transfer.transferId()))
                .isInstanceOfSatisfying(
                        ApplicationException.class,
                        e ->
                                assertThat(e.getStatusCode())
                                        .isEqualTo(NettingStatusCode.NETTING_ACCESS_DENIED));
        as(f.a());
        assertThatThrownBy(() -> executions.getExecution(f.group(), queued.executionId()))
                .isInstanceOfSatisfying(
                        ApplicationException.class,
                        e ->
                                assertThat(e.getStatusCode())
                                        .isEqualTo(NettingStatusCode.NETTING_ACCESS_DENIED));
        assertThat(instructions.getInstruction(f.group(), n.nettingId(), transfer.transferId()))
                .isNotNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"NONE", "LEFT", "REMOVED"})
    void nonActiveMembersCannotEnqueueOrReadCachedEstimate(String membership) throws Exception {
        var f = fixture(15000, 10000);
        as(f.a());
        assertThat(estimates.estimate(f.group()).canCompress()).isTrue();
        long requester = user("requester");
        if (!membership.equals("NONE")) {
            member(f.group(), requester, "MEMBER", LocalDateTime.now(ZoneOffset.UTC));
            leave(f.group(), requester, membership);
        }
        as(requester);
        mvc.perform(
                        post("/api/v2/groups/{g}/settlement-nettings", f.group())
                                .with(
                                        org.springframework.security.test.web.servlet.request
                                                .SecurityMockMvcRequestPostProcessors.user("test")
                                                .roles("USER"))
                                .header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isForbidden());
        assertThatThrownBy(() -> estimates.estimate(f.group()))
                .isInstanceOfSatisfying(
                        ApplicationException.class,
                        e ->
                                assertThat(e.getStatusCode())
                                        .isEqualTo(GroupStatusCode.GROUP_ACCESS_DENIED));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM group_netting_executions WHERE group_id=?",
                                Integer.class,
                                f.group()))
                .isZero();
        verifyNoInteractions(bank);
    }

    @Test
    void requesterLeavingBeforeBankSyncFailsWithoutQueryOrGeneration() {
        var f = fixture(15000, 10000);
        long requester = user("requester");
        member(f.group(), requester, "MEMBER", LocalDateTime.now(ZoneOffset.UTC));
        as(requester);
        var queued =
                executions.execute(f.group(), new ExecuteGroupNettingCommand(UUID.randomUUID()));
        leave(f.group(), requester, "LEFT");
        dispatcher.dispatch();
        assertThat(store.find(f.group(), queued.executionId()).orElseThrow().status())
                .isEqualTo(NettingExecutionStorePort.Status.FAILED);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM group_settlement_nettings WHERE group_id=?",
                                Integer.class,
                                f.group()))
                .isZero();
        verifyNoInteractions(bank);
    }

    @Test
    void requesterLeavingAfterSyncCannotCommitSnapshot() {
        var f = fixture(15000, 10000);
        long requester = user("requester");
        member(f.group(), requester, "MEMBER", LocalDateTime.now(ZoneOffset.UTC));
        as(requester);
        var queued =
                executions.execute(f.group(), new ExecuteGroupNettingCommand(UUID.randomUUID()));
        var claimed =
                store.claimNext(UUID.randomUUID(), LocalDateTime.now(ZoneOffset.UTC)).orElseThrow();
        assertThat(claimed.id()).isEqualTo(queued.executionId());
        var snapshot = worker.synchronize(claimed.id(), claimed.leaseToken()).snapshot();
        leave(f.group(), requester, "LEFT");
        assertThatThrownBy(
                        () ->
                                worker.commit(
                                        new CommitNettingExecutionCommand(
                                                claimed.id(), claimed.leaseToken(), snapshot)))
                .isInstanceOfSatisfying(
                        ApplicationException.class,
                        e ->
                                assertThat(e.getStatusCode())
                                        .isEqualTo(GroupStatusCode.GROUP_ACCESS_DENIED));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM group_settlement_nettings WHERE group_id=?",
                                Integer.class,
                                f.group()))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM group_netting_execution_snapshots WHERE"
                                        + " execution_id=?",
                                Integer.class,
                                queued.executionId()))
                .isZero();
    }

    @Test
    void anotherMemberCanRecompressAfterPreviousNonPartyRequesterLeaves() {
        var f = fixture(15000, 10000);
        long requester = user("nonPartyRequester");
        member(f.group(), requester, "MEMBER", LocalDateTime.now(ZoneOffset.UTC));
        var first = compress(f, requester);
        leave(f.group(), requester, "LEFT");
        var next =
                original(
                        f.group(),
                        f.b(),
                        f.a(),
                        f.accountB(),
                        2000,
                        LocalDateTime.now(ZoneOffset.UTC));
        var second = compress(f, f.b());
        assertThat(second.version()).isEqualTo(2);
        assertThat(second.transfers())
                .singleElement()
                .satisfies(
                        t -> {
                            assertThat(t.payerId()).isEqualTo(f.a());
                            assertThat(t.receiverId()).isEqualTo(f.b());
                            assertThat(t.amount()).isEqualTo(7000);
                        });
        assertThat(second.sources())
                .extracting(GroupNettingResult.Source::settlementId)
                .containsExactlyInAnyOrder(f.ab(), f.ba(), next);
        assertThat(nettings.get(f.group(), first.nettingId()).status()).isEqualTo("SUPERSEDED");
        assertThat(nettings.getBySource(f.ab()).nettingId()).isEqualTo(second.nettingId());
        verify(bank, atLeastOnce())
                .fetch(
                        eq("test-connected-" + f.a()),
                        eq("0088"),
                        eq(number(f.a())),
                        any(),
                        any(),
                        any());
        verify(bank, atLeastOnce())
                .fetch(
                        eq("test-connected-" + f.b()),
                        eq("0088"),
                        eq(number(f.b())),
                        any(),
                        any(),
                        any());
        verify(bank, never())
                .fetch(
                        eq("test-connected-" + requester),
                        anyString(),
                        anyString(),
                        any(),
                        any(),
                        any());
    }

    private void leave(long group, long userId, String status) {
        jdbc.update(
                "UPDATE group_members SET status=?,left_at=now() AT TIME ZONE 'UTC'"
                        + ",updated_at=now() AT TIME ZONE 'UTC' WHERE group_id=? AND user_id=?",
                status,
                group,
                userId);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "CODEF_UPSTREAM_UNAVAILABLE",
                "CODEF_RATE_LIMITED",
                "TEMPORARILY_UNAVAILABLE"
            })
    void temporaryBankFailureRetriesSameRequestAndOldLeaseCannotOverwriteSuccess(String code) {
        var f = fixture(15000, 10000);
        as(f.b());
        var command = new ExecuteGroupNettingCommand(UUID.randomUUID());
        var queued = executions.execute(f.group(), command);
        StatusCode failure =
                code.equals("TEMPORARILY_UNAVAILABLE")
                        ? CommonStatusCode.TEMPORARILY_UNAVAILABLE
                        : ExternalConnectionStatusCode.valueOf(code);
        var firstLease = new java.util.concurrent.atomic.AtomicReference<UUID>();
        when(bank.fetch(anyString(), anyString(), anyString(), any(), any(), any()))
                .thenAnswer(
                        inv -> {
                            assertThat(
                                            TransactionSynchronizationManager
                                                    .isActualTransactionActive())
                                    .isFalse();
                            if (firstLease.get() == null) {
                                firstLease.set(
                                        store.findById(queued.executionId())
                                                .orElseThrow()
                                                .leaseToken());
                                throw ApplicationException.of(failure);
                            }
                            return List.of();
                        });
        dispatcher.dispatch();
        var retry = executions.getExecution(f.group(), queued.executionId());
        assertThat(retry.status()).isEqualTo("RETRY");
        assertThat(retry.errorCode()).isEqualTo(code);
        assertThat(executions.execute(f.group(), command)).isEqualTo(retry);
        assertThat(store.findById(queued.executionId()).orElseThrow().leaseToken()).isNull();
        var retryRow =
                jdbc.queryForMap(
                        "SELECT attempt_count,next_attempt_at,finished_at FROM"
                            + " group_netting_executions WHERE id=?",
                        queued.executionId());
        assertThat(retryRow.get("attempt_count")).isEqualTo(1);
        assertThat(retryRow.get("next_attempt_at")).isNotNull();
        assertThat(retryRow.get("finished_at")).isNull();
        makeRetryDue(queued.executionId());
        dispatcher.dispatch();
        var done = executions.getExecution(f.group(), queued.executionId());
        assertThat(done.status()).as("error %s", done.errorCode()).isEqualTo("SUCCEEDED");
        assertThat(done.errorCode()).isNull();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT attempt_count FROM group_netting_executions WHERE id=?",
                                Integer.class,
                                queued.executionId()))
                .isEqualTo(2);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM group_settlement_nettings WHERE group_id=?",
                                Integer.class,
                                f.group()))
                .isEqualTo(1);
        assertThat(nettings.get(f.group(), done.nettingId()).transfers().getFirst().amount())
                .isEqualTo(5000);
        worker.failed(queued.executionId(), firstLease.get(), "NETTING_WORKER_FAILED");
        assertThat(executions.getExecution(f.group(), queued.executionId())).isEqualTo(done);
    }

    @Test
    void persistentProviderFailureStopsAtMaxAttemptsAndKeepsItsConcreteCode() {
        var f = fixture(15000, 10000);
        as(f.a());
        var command = new ExecuteGroupNettingCommand(UUID.randomUUID());
        var queued = executions.execute(f.group(), command);
        when(bank.fetch(anyString(), anyString(), anyString(), any(), any(), any()))
                .thenThrow(
                        ApplicationException.of(
                                ExternalConnectionStatusCode.CODEF_UPSTREAM_UNAVAILABLE));
        for (int attempt = 1; attempt <= 3; attempt++) {
            dispatcher.dispatch();
            var result = executions.getExecution(f.group(), queued.executionId());
            assertThat(result.errorCode()).isEqualTo("CODEF_UPSTREAM_UNAVAILABLE");
            assertThat(result.status()).isEqualTo(attempt < 3 ? "RETRY" : "FAILED");
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT attempt_count FROM group_netting_executions WHERE id=?",
                                    Integer.class,
                                    queued.executionId()))
                    .isEqualTo(attempt);
            if (attempt < 3) makeRetryDue(queued.executionId());
        }
        dispatcher.dispatch();
        verify(bank, times(3)).fetch(anyString(), anyString(), anyString(), any(), any(), any());
        var row =
                jdbc.queryForMap(
                        "SELECT next_attempt_at,finished_at FROM group_netting_executions WHERE"
                            + " id=?",
                        queued.executionId());
        assertThat(row.get("next_attempt_at")).isNull();
        assertThat(row.get("finished_at")).isNotNull();
        assertThat(executions.execute(f.group(), command).executionId())
                .isEqualTo(queued.executionId());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM group_settlement_nettings WHERE group_id=?",
                                Integer.class,
                                f.group()))
                .isZero();
    }

    @ParameterizedTest
    @EnumSource(
            value = ExternalConnectionStatusCode.class,
            names = {
                "BANK_CREDENTIAL_INVALID",
                "CODEF_ADDITIONAL_AUTH_REQUIRED",
                "CODEF_CONFIGURATION_INVALID"
            })
    void permanentProviderErrorsAreNotAutomaticallyRetried(ExternalConnectionStatusCode code) {
        var f = fixture(15000, 10000);
        as(f.a());
        var queued =
                executions.execute(f.group(), new ExecuteGroupNettingCommand(UUID.randomUUID()));
        when(bank.fetch(anyString(), anyString(), anyString(), any(), any(), any()))
                .thenThrow(ApplicationException.of(code));
        dispatcher.dispatch();
        var result = executions.getExecution(f.group(), queued.executionId());
        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.errorCode()).isEqualTo(code.getCode());
        dispatcher.dispatch();
        verify(bank, times(1)).fetch(anyString(), anyString(), anyString(), any(), any(), any());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT attempt_count FROM group_netting_executions WHERE id=?",
                                Integer.class,
                                queued.executionId()))
                .isEqualTo(1);
    }

    private void makeRetryDue(UUID executionId) {
        var now = LocalDateTime.now(ZoneOffset.UTC);
        assertThat(
                        jdbc.update(
                                "UPDATE group_netting_executions SET next_attempt_at=?,updated_at=?"
                                    + " WHERE id=? AND status='RETRY'",
                                now.minusSeconds(1),
                                now,
                                executionId))
                .isEqualTo(1);
    }

    @Test
    void changedSnapshotStopsCommitWithoutCreatingGeneration() {
        var f = fixture(15000, 10000);
        as(f.a());
        var q = executions.execute(f.group(), new ExecuteGroupNettingCommand(UUID.randomUUID()));
        var claimed =
                store.claimNext(UUID.randomUUID(), LocalDateTime.now(ZoneOffset.UTC)).orElseThrow();
        assertThat(claimed.id()).isEqualTo(q.executionId());
        var snapshot = worker.synchronize(claimed.id(), claimed.leaseToken()).snapshot();
        jdbc.update("UPDATE settlements SET version=version+1 WHERE id=?", f.ab());
        assertThatThrownBy(
                        () ->
                                worker.commit(
                                        new CommitNettingExecutionCommand(
                                                claimed.id(), claimed.leaseToken(), snapshot)))
                .isInstanceOfSatisfying(
                        ApplicationException.class,
                        e ->
                                assertThat(e.getStatusCode())
                                        .isEqualTo(NettingStatusCode.NETTING_INPUT_CHANGED));
        store.finish(
                claimed.id(),
                claimed.leaseToken(),
                NettingExecutionStorePort.Status.STALE,
                null,
                "NETTING_INPUT_CHANGED",
                LocalDateTime.now(ZoneOffset.UTC));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM group_settlement_nettings WHERE group_id=?",
                                Integer.class,
                                f.group()))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM group_netting_execution_snapshots WHERE"
                                        + " execution_id=?",
                                Integer.class,
                                q.executionId()))
                .isZero();
    }

    @Autowired ResolveNettingDepositReviewUseCase reviewUseCase;
    @Autowired NettingDepositStorePort depositStore;

    @Autowired
    com.amateur.howmuchbackend.application.settlement.service.SettlementTransferInstructionReader
            originalInstructions;

    @Autowired
    com.amateur.howmuchbackend.application.port.out.settlement.SettlementReceivingAccountUsagePort
            accountUsage;

    @Autowired
    com.amateur.howmuchbackend.application.port.out.group.LoadGroupMemberSettlementStatePort
            memberState;

    @Autowired javax.sql.DataSource dataSource;

    @Autowired
    com.amateur.howmuchbackend.application.port.in.settlement.GetSettlementUnpaidSummaryUseCase
            unpaid;

    @Test
    void lateOldReferenceIsReviewedOnCurrentGenerationAndReturnedClaimStaysPermanent() {
        var f = fixture(15000, 10000);
        var first = compress(f);
        var old = first.transfers().getFirst();
        original(f.group(), f.a(), f.b(), f.accountA(), 1000, LocalDateTime.now(ZoneOffset.UTC));
        var current = compress(f);
        long tx = income(f.accountB(), 200, old.reference());
        as(f.b());
        var q = reconciliation.reconcile(f.group(), current.nettingId(), UUID.randomUUID());
        dispatcher.dispatch();
        assertThat(executions.getExecution(f.group(), q.executionId()).status())
                .isEqualTo("SUCCEEDED");
        var reviews = depositStore.findReviews(current.nettingId());
        assertThat(reviews).hasSize(1);
        assertThat(reviews.getFirst().reason()).isEqualTo("STALE_REFERENCE");
        original(f.group(), f.a(), f.b(), f.accountA(), 500, LocalDateTime.now(ZoneOffset.UTC));
        assertThat(
                        nettings.get(f.group(), current.nettingId())
                                .transfers()
                                .getFirst()
                                .confirmedAmount())
                .isZero();
        as(f.a());
        var blocked =
                executions.execute(f.group(), new ExecuteGroupNettingCommand(UUID.randomUUID()));
        dispatcher.dispatch();
        assertThat(executions.getExecution(f.group(), blocked.executionId()).status())
                .isEqualTo("STALE");
        long admin = user("Reviewer");
        jdbc.update("UPDATE users SET role='ADMIN' WHERE id=?", admin);
        as(admin);
        reviewUseCase.resolve(
                reviews.getFirst().id(),
                new ResolveNettingReviewCommand("RETURNED", null, "Returned test payment"));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT target_type FROM settlement_bank_transaction_claims WHERE"
                                        + " account_transaction_id=?",
                                String.class,
                                tx))
                .isEqualTo("REVIEW");
        var next = compress(f);
        assertThat(next.version()).isEqualTo(3);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM settlement_bank_transaction_claims WHERE"
                                        + " account_transaction_id=?",
                                Integer.class,
                                tx))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM netted_transfer_deposits WHERE"
                                        + " account_transaction_id=?",
                                Integer.class,
                                tx))
                .isZero();
    }

    @Test
    void reviewedPaymentCountsTowardResidualWithoutRewritingItsClaim() {
        var f = fixture(15000, 10000);
        var first = compress(f);
        var transfer = first.transfers().getFirst();
        long tx = income(f.accountB(), 1200, "No reference");
        as(f.b());
        reconciliation.reconcile(f.group(), first.nettingId(), UUID.randomUUID());
        dispatcher.dispatch();
        var review = depositStore.findReviews(first.nettingId()).getFirst();
        long admin = user("Reviewer");
        jdbc.update("UPDATE users SET role='ADMIN' WHERE id=?", admin);
        as(admin);
        reviewUseCase.resolve(
                review.id(),
                new ResolveNettingReviewCommand(
                        "APPLIED_TO_NETTING", transfer.transferId(), "Verified payment"));
        as(f.b());
        assertThat(
                        nettings.get(f.group(), first.nettingId())
                                .transfers()
                                .getFirst()
                                .confirmedAmount())
                .isEqualTo(1200);
        assertThat(unpaid.getSummary().remainingReceivableAmount()).isEqualTo(3800);
        original(f.group(), f.a(), f.b(), f.accountA(), 3000, LocalDateTime.now(ZoneOffset.UTC));
        var second = compress(f);
        assertThat(second.transfers())
                .singleElement()
                .satisfies(t -> assertThat(t.amount()).isEqualTo(800));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT carried_amount FROM group_netting_rollover_transfers WHERE"
                                        + " successor_netting_id=?",
                                Long.class,
                                second.nettingId()))
                .isEqualTo(3800);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT target_type FROM settlement_bank_transaction_claims WHERE"
                                        + " account_transaction_id=?",
                                String.class,
                                tx))
                .isEqualTo("REVIEW");
        as(f.b());
        assertThat(unpaid.getSummary().remainingReceivableAmount()).isEqualTo(800);
    }

    @Test
    void cancellingResidualByNewDebtCompletesAllAncestorOriginals() {
        var f = fixture(15000, 10000);
        var first = compress(f);
        income(f.accountB(), 2000, first.transfers().getFirst().reference());
        as(f.b());
        reconciliation.reconcile(f.group(), first.nettingId(), UUID.randomUUID());
        dispatcher.dispatch();
        UUID reverse =
                original(
                        f.group(),
                        f.a(),
                        f.b(),
                        f.accountA(),
                        3000,
                        LocalDateTime.now(ZoneOffset.UTC));
        var second = compress(f);
        assertThat(second.status()).isEqualTo("COMPLETED");
        assertThat(second.transfers()).isEmpty();
        assertThat(
                        jdbc.queryForList(
                                "SELECT status FROM settlements WHERE id IN (?,?,?)",
                                String.class,
                                f.ab(),
                                f.ba(),
                                reverse))
                .containsOnly("COMPLETED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM netted_transfer_deposits WHERE netting_id=?",
                                Integer.class,
                                first.nettingId()))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM netted_transfer_deposits WHERE netting_id=?",
                                Integer.class,
                                second.nettingId()))
                .isZero();
    }

    @Test
    void expiredWorkerTokenCannotWriteAfterRecovery() {
        var f = fixture(15000, 10000);
        var now = LocalDateTime.now(ZoneOffset.UTC);
        var queued =
                store.enqueue(
                        f.group(),
                        null,
                        f.a(),
                        NettingExecutionStorePort.Kind.CREATE,
                        UUID.randomUUID(),
                        now.minusMinutes(2));
        var first = store.claimNext(UUID.randomUUID(), now.minusSeconds(119)).orElseThrow();
        var recovered = store.claimNext(UUID.randomUUID(), now).orElseThrow();
        assertThat(recovered.id()).isEqualTo(queued.id());
        assertThat(recovered.leaseToken()).isNotEqualTo(first.leaseToken());
        assertThatThrownBy(() -> store.requireLease(first.id(), first.leaseToken(), now))
                .isInstanceOfSatisfying(
                        ApplicationException.class,
                        e ->
                                assertThat(e.getStatusCode())
                                        .isEqualTo(NettingStatusCode.NETTING_EXECUTION_FENCED));
        store.requireLease(recovered.id(), recovered.leaseToken(), now);
    }

    @Test
    void productionPairSelectionIsSavedAndExecutedByRealBatch() {
        List<Long> users = new ArrayList<>();
        for (int i = 0; i < 8; i++) users.add(user("Pair" + i));
        var now = LocalDateTime.now(ZoneOffset.UTC).minusDays(1);
        long a = users.get(0), b = users.get(1), c = users.get(2);
        long group =
                jdbc.queryForObject(
                        "INSERT INTO"
                            + " groups(name,owner_id,invite_code,status,creator_id,created_at,updated_at)"
                            + " VALUES('pair',?,?,'ACTIVE',?,?,?) RETURNING id",
                        Long.class,
                        a,
                        UUID.randomUUID()
                                .toString()
                                .replace("-", "")
                                .substring(0, 8)
                                .toUpperCase(Locale.ROOT),
                        a,
                        now,
                        now);
        fixtureGroups.add(group);
        for (int i = 0; i < users.size(); i++)
            member(group, users.get(i), i == 0 ? "OWNER" : "MEMBER", now);
        long aa = account(a), bb = account(b), cc = account(c), hh = account(users.get(7));
        UUID ab = original(group, a, users.get(3), aa, 60000, now);
        original(group, a, users.get(4), aa, 20000, now);
        original(group, b, users.get(4), bb, 30000, now);
        original(group, b, users.get(5), bb, 10000, now);
        original(group, c, users.get(5), cc, 20000, now);
        original(group, users.get(7), users.get(6), hh, 7777, now);
        var n = compress(new Fixture(group, a, b, aa, bb, ab, ab));
        assertThat(n.transfers()).hasSize(5);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT input_json->'calculatorSelection'->'algorithm'->>'name'"
                                    + " FROM group_netting_execution_snapshots WHERE group_id=?",
                                String.class,
                                group))
                .isEqualTo("PAIR_REDUCED_DP_NET");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT input_json->'calculatorSelection'->>'policyVersion' FROM"
                                        + " group_netting_execution_snapshots WHERE group_id=?",
                                String.class,
                                group))
                .isEqualTo("3");
    }

    @Test
    void concurrentIdenticalReconciliationRequestsReturnOneExecution() throws Exception {
        var f = fixture(15000, 10000);
        var n = compress(f);
        as(f.b());
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 4; i++) {
                var go = new java.util.concurrent.CountDownLatch(1);
                UUID key = UUID.randomUUID();
                var first =
                        pool.submit(
                                () -> {
                                    go.await();
                                    return reconciliation.reconcile(f.group(), n.nettingId(), key);
                                });
                var second =
                        pool.submit(
                                () -> {
                                    go.await();
                                    return reconciliation.reconcile(f.group(), n.nettingId(), key);
                                });
                go.countDown();
                assertThat(first.get(5, java.util.concurrent.TimeUnit.SECONDS).executionId())
                        .isEqualTo(
                                second.get(5, java.util.concurrent.TimeUnit.SECONDS).executionId());
            }
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM group_netting_executions WHERE group_id=?"
                                            + " AND kind='RECONCILE'",
                                    Integer.class,
                                    f.group()))
                    .isEqualTo(4);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void lockedExpiredExecutionDoesNotBlockClaimingAnotherGroup() throws Exception {
        var f = fixture(15000, 10000);
        var now = LocalDateTime.now(ZoneOffset.UTC);
        var old =
                store.enqueue(
                        f.group(),
                        null,
                        f.a(),
                        NettingExecutionStorePort.Kind.CREATE,
                        UUID.randomUUID(),
                        now.minusMinutes(2));
        store.claimNext(UUID.randomUUID(), now.minusSeconds(119)).orElseThrow();
        var other = fixture(15000, 10000);
        var ready =
                store.enqueue(
                        other.group(),
                        null,
                        other.a(),
                        NettingExecutionStorePort.Kind.CREATE,
                        UUID.randomUUID(),
                        now);
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (var stmt =
                    connection.prepareStatement(
                            "SELECT id FROM group_netting_executions WHERE id=? FOR UPDATE")) {
                stmt.setObject(1, old.id());
                stmt.executeQuery().close();
            }
            var pool = java.util.concurrent.Executors.newSingleThreadExecutor();
            try {
                assertThat(
                                pool.submit(
                                                () ->
                                                        store.claimNext(UUID.randomUUID(), now)
                                                                .orElseThrow())
                                        .get(3, java.util.concurrent.TimeUnit.SECONDS)
                                        .id())
                        .isEqualTo(ready.id());
            } finally {
                connection.rollback();
                pool.shutdownNow();
            }
        }
    }

    @Test
    void pendingRecompressionBlocksOldInstructionAndAccountIdentityChangeInvalidatesSnapshot() {
        var f = fixture(15000, 10000);
        var n = compress(f);
        original(f.group(), f.b(), f.a(), f.accountB(), 1000, LocalDateTime.now(ZoneOffset.UTC));
        as(f.a());
        var queued =
                executions.execute(f.group(), new ExecuteGroupNettingCommand(UUID.randomUUID()));
        assertThatThrownBy(
                        () ->
                                instructions.getInstruction(
                                        f.group(),
                                        n.nettingId(),
                                        n.transfers().getFirst().transferId()))
                .isInstanceOfSatisfying(
                        ApplicationException.class,
                        e ->
                                assertThat(e.getStatusCode())
                                        .isEqualTo(NettingStatusCode.NETTING_PROCESSING));
        var claimed =
                store.claimNext(UUID.randomUUID(), LocalDateTime.now(ZoneOffset.UTC)).orElseThrow();
        var snapshot = worker.synchronize(claimed.id(), claimed.leaseToken()).snapshot();
        var now = LocalDateTime.now(ZoneOffset.UTC);
        long connection =
                jdbc.queryForObject(
                        "SELECT external_connection_id FROM accounts WHERE id=?",
                        Long.class,
                        f.accountA());
        jdbc.update(
                "UPDATE accounts SET external_account_key_ciphertext=? WHERE id=?",
                cipher.encryptAccountKey(f.a(), connection, number(f.a()) + "9"),
                f.accountA());
        assertThatThrownBy(
                        () ->
                                worker.commit(
                                        new CommitNettingExecutionCommand(
                                                claimed.id(), claimed.leaseToken(), snapshot)))
                .isInstanceOfSatisfying(
                        ApplicationException.class,
                        e ->
                                assertThat(e.getStatusCode())
                                        .isEqualTo(NettingStatusCode.NETTING_INPUT_CHANGED));
        store.finish(
                claimed.id(),
                claimed.leaseToken(),
                NettingExecutionStorePort.Status.STALE,
                null,
                "NETTING_INPUT_CHANGED",
                now);
        assertThat(nettings.get(f.group(), n.nettingId()).status()).isEqualTo("ACTIVE");
        jdbc.update(
                "UPDATE accounts SET external_account_key_ciphertext=? WHERE id=?",
                cipher.encryptAccountKey(f.a(), connection, number(f.a())),
                f.accountA());
        assertThat(compress(f).version()).isEqualTo(2);
    }

    @Test
    void currentReceivingAccountAndCreatorOnlyPartyRemainProtected() {
        var f = fixture(15000, 10000);
        jdbc.update("UPDATE accounts SET is_default=false WHERE user_id=?", f.b());
        long newDefault = account(f.b());
        var n = compress(f);
        assertThat(n.transfers()).hasSize(1);
        assertThat(accountUsage.existsInProgressByReceivingAccountId(newDefault)).isTrue();
        assertThat(memberState.findStatuses(f.group(), f.b()))
                .contains(
                        com.amateur.howmuchbackend.domain.settlement.SettlementStatus
                                .NETTING_IN_PROGRESS);
    }

    @Autowired
    com.amateur.howmuchbackend.adapter.out.persistence.settlement.repository.SettlementJpaRepository
            settlements;

    @Test
    void reviewAfterCompletionBlocksDeletionUntilResolved() {
        var f = fixture(10000, 10000);
        var n = compress(f);
        assertThat(n.status()).isEqualTo("COMPLETED");
        assertThat(settlements.existsByUserIdAndStatusNotCompleted(f.a())).isFalse();
        assertThat(accountUsage.existsInProgressByReceivingAccountId(f.accountA())).isFalse();
        income(f.accountA(), 10000, "B");
        as(f.a());
        var queued = reconciliation.reconcile(f.group(), n.nettingId(), UUID.randomUUID());
        dispatcher.dispatch();
        assertThat(executions.getExecution(f.group(), queued.executionId()).status())
                .isEqualTo("SUCCEEDED");
        var reviews = depositStore.findReviews(n.nettingId());
        assertThat(reviews).hasSize(1);
        assertThat(settlements.existsByUserIdAndStatusNotCompleted(f.a())).isTrue();
        assertThat(accountUsage.existsInProgressByReceivingAccountId(f.accountA())).isTrue();
        long admin = user("Reviewer");
        jdbc.update("UPDATE users SET role='ADMIN' WHERE id=?", admin);
        as(admin);
        reviewUseCase.resolve(
                reviews.getFirst().id(),
                new ResolveNettingReviewCommand(
                        "RETURNED", null, "Returned completed-generation deposit"));
        assertThat(settlements.existsByUserIdAndStatusNotCompleted(f.a())).isFalse();
        assertThat(accountUsage.existsInProgressByReceivingAccountId(f.accountA())).isFalse();
    }

    @Test
    void monitoringIdentityChangedDuringLookupCannotBecomeFreshSnapshot() {
        var f = fixture(15000, 10000);
        var first = compress(f);
        original(f.group(), f.b(), f.a(), f.accountB(), 1000, LocalDateTime.now(ZoneOffset.UTC));
        long connection =
                jdbc.queryForObject(
                        "SELECT external_connection_id FROM accounts WHERE id=?",
                        Long.class,
                        f.accountA());
        var changed = new java.util.concurrent.atomic.AtomicBoolean();
        when(bank.fetch(anyString(), anyString(), anyString(), any(), any(), any()))
                .thenAnswer(
                        inv -> {
                            if (number(f.a()).equals(inv.getArgument(2))
                                    && changed.compareAndSet(false, true)) {
                                jdbc.update(
                                        "UPDATE accounts SET external_account_key_ciphertext=?"
                                                + " WHERE id=?",
                                        cipher.encryptAccountKey(
                                                f.a(), connection, number(f.a()) + "9"),
                                        f.accountA());
                            }
                            return List.of();
                        });
        as(f.a());
        var queued =
                executions.execute(f.group(), new ExecuteGroupNettingCommand(UUID.randomUUID()));
        dispatcher.dispatch();
        var result = executions.getExecution(f.group(), queued.executionId());
        assertThat(changed).isTrue();
        assertThat(result.status()).isEqualTo("STALE");
        assertThat(result.errorCode()).isEqualTo("NETTING_INPUT_CHANGED");
        assertThat(nettings.current(f.group()).nettingId()).isEqualTo(first.nettingId());
        jdbc.update(
                "UPDATE accounts SET external_account_key_ciphertext=? WHERE id=?",
                cipher.encryptAccountKey(f.a(), connection, number(f.a())),
                f.accountA());
    }

    @Autowired GetGroupNettingEstimateUseCase estimates;

    private void assertOriginalsHidden(Fixture f) {
        for (long user : List.of(f.a(), f.b())) {
            for (String tab : List.of("RECEIVE", "PAY")) {
                assertThat(settlements.findAllForUser(user, tab, "ALL", f.group(), null, null))
                        .isEmpty();
                assertThat(settlements.findAllForTreasurer(user, tab)).isEmpty();
            }
        }
    }

    @Test
    void estimateIsReadOnlyCountsUnpaidTransfersAndKeepsDirectExecution() throws Exception {
        var f = fixture(15000, 10000);
        as(f.a());
        mvc.perform(
                        get("/api/v2/groups/{g}/settlement-nettings/estimate", f.group())
                                .with(
                                        org.springframework.security.test.web.servlet.request
                                                .SecurityMockMvcRequestPostProcessors.user("test")
                                                .roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.eligibleSettlementCount").value(2))
                .andExpect(jsonPath("$.data.beforeTransferCount").value(2))
                .andExpect(jsonPath("$.data.afterTransferCount").value(1))
                .andExpect(jsonPath("$.data.reducedTransferCount").value(1))
                .andExpect(jsonPath("$.data.canCompress").value(true));
        assertThat(estimates.estimate(f.group()).afterTransferCount()).isEqualTo(1);
        verify(estimateCalculation, times(1)).calculate(f.group());
        verifyNoInteractions(bank);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM group_netting_executions WHERE group_id=?",
                                Integer.class,
                                f.group()))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM group_settlement_nettings WHERE group_id=?",
                                Integer.class,
                                f.group()))
                .isZero();
        as(f.b());
        assertThat(estimates.estimate(f.group()).canCompress()).isTrue();
        verify(estimateCalculation, times(1)).calculate(f.group());
        as(user("outsider"));
        mvc.perform(
                        get("/api/v2/groups/{g}/settlement-nettings/estimate", f.group())
                                .with(
                                        org.springframework.security.test.web.servlet.request
                                                .SecurityMockMvcRequestPostProcessors.user("test")
                                                .roles("USER")))
                .andExpect(status().isForbidden());
        as(f.a());
        var queued =
                executions.execute(f.group(), new ExecuteGroupNettingCommand(UUID.randomUUID()));
        assertThat(estimates.estimate(f.group()).canCompress()).isFalse();
        assertThat(settlements.findAllForUser(f.a(), "RECEIVE", "ALL", f.group(), null, null))
                .hasSize(1);
        dispatcher.dispatch();
        var result = executions.getExecution(f.group(), queued.executionId());
        assertThat(result.status()).isEqualTo("SUCCEEDED");
        mvc.perform(
                        get("/api/v2/groups/{g}/settlement-nettings/current", f.group())
                                .with(
                                        org.springframework.security.test.web.servlet.request
                                                .SecurityMockMvcRequestPostProcessors.user("test")
                                                .roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sources.length()").value(2))
                .andExpect(jsonPath("$.data.sources[0].title").value("original"))
                .andExpect(jsonPath("$.data.sources[0].status").value("NETTING_IN_PROGRESS"))
                .andExpect(jsonPath("$.data.sources[0].requesterName").isNotEmpty())
                .andExpect(jsonPath("$.data.sources[0].requestedAt").isNotEmpty())
                .andExpect(jsonPath("$.data.transfers[0].payerName").value("A"))
                .andExpect(jsonPath("$.data.transfers[0].receiverName").value("B"))
                .andExpect(
                        jsonPath("$.data.transfers[0].payerPublicId")
                                .value(
                                        jdbc.queryForObject(
                                                "SELECT public_id FROM users WHERE id=?",
                                                String.class,
                                                f.a())))
                .andExpect(
                        jsonPath("$.data.transfers[0].receiverPublicId")
                                .value(
                                        jdbc.queryForObject(
                                                "SELECT public_id FROM users WHERE id=?",
                                                String.class,
                                                f.b())))
                .andExpect(jsonPath("$.data.myCategory").doesNotExist())
                .andExpect(jsonPath("$.data.category").doesNotExist());
        assertOriginalsHidden(f);
    }

    @Test
    void unchangedPositiveTransferCountDoesNotOfferCompression() {
        var f = fixture(15000, 10000);
        as(f.a());
        suspendUseCase.suspend(f.ba());
        long c = user("C"), d = user("D");
        var now = LocalDateTime.now(ZoneOffset.UTC);
        member(f.group(), c, "MEMBER", now);
        member(f.group(), d, "MEMBER", now);
        original(f.group(), c, d, account(c), 20000, now);
        clearInvocations(bank);
        var estimate = estimates.estimate(f.group());
        assertThat(estimate.eligibleSettlementCount()).isEqualTo(2);
        assertThat(estimate.beforeTransferCount()).isEqualTo(2);
        assertThat(estimate.afterTransferCount()).isEqualTo(2);
        assertThat(estimate.reducedTransferCount()).isZero();
        assertThat(estimate.canCompress()).isFalse();
        assertThat(estimates.estimate(f.group())).isEqualTo(estimate);
        verifyNoInteractions(bank);
    }

    @Test
    void estimateSupportsFullOffsetAndReturnsZeroWhenNoRemainingDebts() {
        var f = fixture(10000, 10000);
        as(f.a());
        var before = estimates.estimate(f.group());
        assertThat(before.beforeTransferCount()).isEqualTo(2);
        assertThat(before.afterTransferCount()).isZero();
        assertThat(before.reducedTransferCount()).isEqualTo(2);
        assertThat(before.canCompress()).isTrue();
        compress(f);
        var after = estimates.estimate(f.group());
        assertThat(after.eligibleSettlementCount()).isZero();
        assertThat(after.beforeTransferCount()).isZero();
        assertThat(after.afterTransferCount()).isZero();
        assertThat(after.canCompress()).isFalse();
        assertOriginalsHidden(f);
    }

    @Autowired NettingEstimateCachePort estimateCache;

    @Autowired
    com.amateur.howmuchbackend.application.settlement.netting.service
                    .NettingEstimateInvalidationService
            estimateInvalidation;

    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;

    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    com.amateur.howmuchbackend.application.settlement.netting.service
                    .NettingEstimateCalculationReader
            estimateCalculation;

    @Autowired
    com.amateur.howmuchbackend.application.port.in.settlement.SuspendSettlementUseCase
            suspendUseCase;

    @Test
    void estimateInvalidatesOnlyAfterCommitAndKeepsCacheOnRollback() {
        var f = fixture(15000, 10000);
        as(f.a());
        assertThat(estimates.estimate(f.group()).beforeTransferCount()).isEqualTo(2);
        var tx =
                new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        tx.executeWithoutResult(
                status -> {
                    jdbc.update("UPDATE settlements SET status='SUSPENDED' WHERE id=?", f.ab());
                    estimateInvalidation.settlementChanged(f.ab());
                    estimateInvalidation.groupChanged(f.group());
                    assertThat(estimateCache.find(f.group()))
                            .get()
                            .extracting(NettingEstimateCachePort.Counts::beforeTransferCount)
                            .isEqualTo(2);
                    status.setRollbackOnly();
                });
        assertThat(estimates.estimate(f.group()).beforeTransferCount()).isEqualTo(2);
        verify(estimateCalculation, times(1)).calculate(f.group());
        tx.executeWithoutResult(
                status -> {
                    jdbc.update("UPDATE settlements SET status='SUSPENDED' WHERE id=?", f.ab());
                    estimateInvalidation.settlementChanged(f.ab());
                    assertThat(estimateCache.find(f.group())).isPresent();
                });
        assertThat(estimateCache.find(f.group())).isEmpty();
        assertThat(estimates.estimate(f.group()).beforeTransferCount()).isEqualTo(1);
        verify(estimateCalculation, times(2)).calculate(f.group());
    }

    @Test
    void realSuspensionServiceInvalidatesTheEstimateAfterSuccessfulRefresh() {
        var f = fixture(15000, 10000);
        as(f.a());
        assertThat(estimates.estimate(f.group()).beforeTransferCount()).isEqualTo(2);
        // A created ba, so suspending it leaves only ab as an eligible original.
        suspendUseCase.suspend(f.ba());
        assertThat(estimateCache.find(f.group())).isEmpty();
        var after = estimates.estimate(f.group());
        assertThat(after.beforeTransferCount()).isEqualTo(1);
        assertThat(after.afterTransferCount()).isEqualTo(1);
        assertThat(after.canCompress()).isFalse();
    }

    @Test
    void concurrentEstimateRequestsShareOneCalculation() throws Exception {
        var f = fixture(15000, 10000);
        as(f.a());
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        doAnswer(
                        inv -> {
                            entered.countDown();
                            assertThat(release.await(3, java.util.concurrent.TimeUnit.SECONDS))
                                    .isTrue();
                            return inv.callRealMethod();
                        })
                .when(estimateCalculation)
                .calculate(f.group());
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(4)) {
            var first = pool.submit(() -> estimates.estimate(f.group()));
            assertThat(entered.await(3, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var others = new ArrayList<java.util.concurrent.Future<GroupNettingEstimateResult>>();
            for (int i = 0; i < 3; i++)
                others.add(pool.submit(() -> estimates.estimate(f.group())));
            release.countDown();
            var result = first.get(5, java.util.concurrent.TimeUnit.SECONDS);
            for (var future : others)
                assertThat(future.get(5, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(result);
            verify(estimateCalculation, times(1)).calculate(f.group());
        } finally {
            release.countDown();
        }
    }

    @Test
    void changedInputDuringCalculationCannotRepopulateAnOldEstimate() throws Exception {
        var f = fixture(15000, 10000);
        as(f.a());
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var first = new java.util.concurrent.atomic.AtomicBoolean(true);
        doAnswer(
                        inv -> {
                            var result = inv.callRealMethod();
                            if (first.compareAndSet(true, false)) {
                                entered.countDown();
                                assertThat(release.await(3, java.util.concurrent.TimeUnit.SECONDS))
                                        .isTrue();
                            }
                            return result;
                        })
                .when(estimateCalculation)
                .calculate(f.group());
        try (var pool = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var future = pool.submit(() -> estimates.estimate(f.group()));
            assertThat(entered.await(3, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            new org.springframework.transaction.support.TransactionTemplate(transactionManager)
                    .executeWithoutResult(
                            status -> {
                                jdbc.update(
                                        "UPDATE settlements SET status='SUSPENDED' WHERE id=?",
                                        f.ab());
                                estimateInvalidation.settlementChanged(f.ab());
                            });
            release.countDown();
            var result = future.get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(result.beforeTransferCount()).isEqualTo(1);
            assertThat(result.afterTransferCount()).isEqualTo(1);
            assertThat(estimateCache.find(f.group()))
                    .get()
                    .extracting(NettingEstimateCachePort.Counts::beforeTransferCount)
                    .isEqualTo(1);
            verify(estimateCalculation, times(2)).calculate(f.group());
        } finally {
            release.countDown();
        }
    }

    @Test
    void oneEligibleOriginalIsShownButCannotBeCompressedThroughHttp() throws Exception {
        var f = fixture(15000, 10000);
        as(f.a());
        suspendUseCase.suspend(f.ba());
        clearInvocations(bank);
        var estimate = estimates.estimate(f.group());
        assertThat(estimate.eligibleSettlementCount()).isEqualTo(1);
        assertThat(estimate.beforeTransferCount()).isEqualTo(1);
        assertThat(estimate.afterTransferCount()).isEqualTo(1);
        assertThat(estimate.canCompress()).isFalse();
        assertThat(estimates.estimate(f.group())).isEqualTo(estimate);
        mvc.perform(
                        post("/api/v2/groups/{g}/settlement-nettings", f.group())
                                .with(
                                        org.springframework.security.test.web.servlet.request
                                                .SecurityMockMvcRequestPostProcessors.user("test")
                                                .roles("USER"))
                                .header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("NETTING_INSUFFICIENT_SETTLEMENTS"));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM group_netting_executions WHERE group_id=?",
                                Integer.class,
                                f.group()))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM settlements WHERE id=?", String.class, f.ab()))
                .isEqualTo("AWAITING_DEPOSIT");
        verifyNoInteractions(bank);
    }

    @Test
    void activeCompressionAloneIsBlockedButOneNewOriginalAllowsRecompression() {
        var f = fixture(15000, 10000);
        var first = compress(f);
        assertThat(estimates.estimate(f.group()).eligibleSettlementCount()).isZero();
        assertThat(estimates.estimate(f.group()).canCompress()).isFalse();
        assertThatThrownBy(
                        () ->
                                executions.execute(
                                        f.group(),
                                        new ExecuteGroupNettingCommand(UUID.randomUUID())))
                .isInstanceOfSatisfying(
                        ApplicationException.class,
                        e ->
                                assertThat(e.getStatusCode())
                                        .isEqualTo(
                                                NettingStatusCode
                                                        .NETTING_INSUFFICIENT_SETTLEMENTS));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM group_netting_executions WHERE group_id=?",
                                Integer.class,
                                f.group()))
                .isEqualTo(1);
        original(f.group(), f.a(), f.b(), f.accountA(), 1000, LocalDateTime.now(ZoneOffset.UTC));
        // The fixture writes SQL directly; production creation invalidates through its Service.
        estimateInvalidation.groupChanged(f.group());
        var estimate = estimates.estimate(f.group());
        assertThat(estimate.eligibleSettlementCount()).isEqualTo(1);
        assertThat(estimate.beforeTransferCount()).isEqualTo(2);
        assertThat(estimate.afterTransferCount()).isEqualTo(1);
        assertThat(estimate.canCompress()).isTrue();
        var second = compress(f, f.b());
        assertThat(second.transfers())
                .singleElement()
                .satisfies(t -> assertThat(t.amount()).isEqualTo(4000));
        assertThat(nettings.get(f.group(), first.nettingId()).status()).isEqualTo("SUPERSEDED");
    }

    @Test
    void replayOfSuccessfulRequestStillReturnsItWhenOnlyItsCompressionRemains() {
        var f = fixture(15000, 10000);
        as(f.b());
        var command = new ExecuteGroupNettingCommand(UUID.randomUUID());
        var queued = executions.execute(f.group(), command);
        dispatcher.dispatch();
        var done = executions.getExecution(f.group(), queued.executionId());
        assertThat(done.status()).isEqualTo("SUCCEEDED");
        assertThat(executions.execute(f.group(), command)).isEqualTo(done);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM group_netting_executions WHERE group_id=?",
                                Integer.class,
                                f.group()))
                .isEqualTo(1);
    }

    @Test
    void originalPaymentDuringSyncIsPreservedAndStopsCompressionWhenOneTargetRemains() {
        var f = fixture(15000, 10000);
        as(f.a());
        var queued =
                executions.execute(f.group(), new ExecuteGroupNettingCommand(UUID.randomUUID()));
        long tx = income(f.accountB(), 15000, "A");
        dispatcher.dispatch();
        var result = executions.getExecution(f.group(), queued.executionId());
        assertThat(result.status()).isEqualTo("STALE");
        assertThat(result.errorCode()).isEqualTo("NETTING_INSUFFICIENT_SETTLEMENTS");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM settlements WHERE id=?", String.class, f.ab()))
                .isEqualTo("COMPLETED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM settlements WHERE id=?", String.class, f.ba()))
                .isEqualTo("AWAITING_DEPOSIT");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM settlement_deposits WHERE"
                                        + " account_transaction_id=?",
                                Integer.class,
                                tx))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM group_settlement_nettings WHERE group_id=?",
                                Integer.class,
                                f.group()))
                .isZero();
    }

    @Test
    void scopeReductionAfterSyncCannotCreateGenerationAtCommit() {
        var f = fixture(15000, 10000);
        as(f.a());
        var queued =
                executions.execute(f.group(), new ExecuteGroupNettingCommand(UUID.randomUUID()));
        var claimed =
                store.claimNext(UUID.randomUUID(), LocalDateTime.now(ZoneOffset.UTC)).orElseThrow();
        assertThat(claimed.id()).isEqualTo(queued.executionId());
        var snapshot = worker.synchronize(claimed.id(), claimed.leaseToken()).snapshot();
        jdbc.update("UPDATE settlements SET status='SUSPENDED' WHERE id=?", f.ba());
        assertThatThrownBy(
                        () ->
                                worker.commit(
                                        new CommitNettingExecutionCommand(
                                                claimed.id(), claimed.leaseToken(), snapshot)))
                .isInstanceOfSatisfying(
                        ApplicationException.class,
                        e ->
                                assertThat(e.getStatusCode())
                                        .isEqualTo(
                                                NettingStatusCode
                                                        .NETTING_INSUFFICIENT_SETTLEMENTS));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM group_settlement_nettings WHERE group_id=?",
                                Integer.class,
                                f.group()))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM group_netting_execution_snapshots WHERE"
                                        + " execution_id=?",
                                Integer.class,
                                queued.executionId()))
                .isZero();
    }

    @Test
    void compressedPaymentUpdatesOnlyFinalOutstandingAndCompletesOriginalHistory() {
        var f = fixture(15000, 10000);
        as(f.b());
        assertThat(unpaid.getSummary().remainingReceivableAmount()).isEqualTo(15000);
        var n = compress(f, f.b());
        var transfer = n.transfers().getFirst();
        as(f.b());
        assertThat(unpaid.getSummary().remainingReceivableAmount()).isEqualTo(5000);
        assertThat(unpaid.getSummary().unpaidParticipantCount()).isEqualTo(1);
        long partial = income(f.accountB(), 2000, transfer.reference());
        var refreshed = reconciliation.reconcile(f.group(), n.nettingId(), UUID.randomUUID());
        dispatcher.dispatch();
        assertThat(executions.getExecution(f.group(), refreshed.executionId()).status())
                .isEqualTo("SUCCEEDED");
        assertThat(unpaid.getSummary().remainingReceivableAmount()).isEqualTo(3000);
        income(f.accountB(), 3000, transfer.reference());
        refreshed = reconciliation.reconcile(f.group(), n.nettingId(), UUID.randomUUID());
        dispatcher.dispatch();
        assertThat(executions.getExecution(f.group(), refreshed.executionId()).status())
                .isEqualTo("SUCCEEDED");
        var completed = nettings.get(f.group(), n.nettingId());
        assertThat(completed.status()).isEqualTo("COMPLETED");
        assertThat(completed.transfers().getFirst().confirmedAmount()).isEqualTo(5000);
        assertThat(completed.sources())
                .allSatisfy(source -> assertThat(source.status()).isEqualTo("COMPLETED"));
        assertThat(unpaid.getSummary().remainingReceivableAmount()).isZero();
        assertThat(unpaid.getSummary().unpaidParticipantCount()).isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM settlement_deposits WHERE settlement_id IN"
                                        + " (?,?)",
                                Integer.class,
                                f.ab(),
                                f.ba()))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM settlement_bank_transaction_claims WHERE"
                                        + " account_transaction_id=?",
                                Integer.class,
                                partial))
                .isEqualTo(1);
        assertOriginalsHidden(f);
    }

    @Test
    void unchangedDepositRefreshKeepsCachedEstimate() {
        var f = fixture(15000, 10000);
        var n = compress(f);
        as(f.a());
        estimates.estimate(f.group());
        var cached = estimateCache.find(f.group()).orElseThrow();
        as(f.b());
        var queued = reconciliation.reconcile(f.group(), n.nettingId(), UUID.randomUUID());
        dispatcher.dispatch();
        assertThat(executions.getExecution(f.group(), queued.executionId()).status())
                .isEqualTo("SUCCEEDED");
        assertThat(estimateCache.find(f.group())).contains(cached);
        estimates.estimate(f.group());
        verify(estimateCalculation, times(1)).calculate(f.group());
    }

    private long user(String name) {
        String unique = UUID.randomUUID().toString();
        var now = LocalDateTime.now(ZoneOffset.UTC).minusDays(2);
        return jdbc.queryForObject(
                "INSERT INTO"
                    + " users(identity_key,name,public_id,phone_number,pin_hash,phone_verified,role,active,required_terms_agreed_at,created_at,updated_at)"
                    + " VALUES(?,?,?,?,'unused',true,'USER',true,?,?,?) RETURNING id",
                Long.class,
                unique,
                name,
                "u" + unique.replace("-", "").substring(0, 15),
                "p" + unique.replace("-", "").substring(0, 12),
                now,
                now,
                now);
    }

    private String number(long user) {
        return "100200" + user;
    }

    private long account(long user) {
        var now = LocalDateTime.now(ZoneOffset.UTC).minusDays(1);
        var connections =
                jdbc.queryForList(
                        "SELECT id FROM external_connections WHERE user_id=?", Long.class, user);
        long connection;
        if (connections.isEmpty()) {
            connection =
                    jdbc.queryForObject(
                            "INSERT INTO"
                                + " external_connections(user_id,connected_id_ciphertext,configuration_fingerprint,active,created_at,updated_at)"
                                + " VALUES(?,?,?,true,?,?) RETURNING id",
                            Long.class,
                            user,
                            cipher.encryptConnectedId(user, "test-connected-" + user),
                            fingerprint.current(),
                            now,
                            now);
            jdbc.update(
                    "INSERT INTO"
                        + " connected_institutions(external_connection_id,organization,status,created_at,updated_at)"
                        + " VALUES(?,'0088','CONNECTED',?,?)",
                    connection,
                    now,
                    now);
        } else connection = connections.getFirst();
        int count =
                jdbc.queryForObject(
                        "SELECT count(*) FROM accounts WHERE user_id=?", Integer.class, user);
        String actualNumber = number(user) + (count == 0 ? "" : Integer.toString(count + 1));
        return jdbc.queryForObject(
                "INSERT INTO"
                    + " accounts(user_id,external_connection_id,organization,candidate_key,external_account_key_ciphertext,type,name,masked_number,balance,currency,active,created_at,updated_at,is_default,display_order,product_code)"
                    + " VALUES(?,?,'0088',?,?,'DEPOSIT_TRUST','account','****1234',100000,'KRW',true,?,?,true,0,'11')"
                    + " RETURNING id",
                Long.class,
                user,
                connection,
                UUID.randomUUID().toString().replace("-", "") + "a".repeat(32),
                cipher.encryptAccountKey(user, connection, actualNumber),
                now,
                now);
    }

    private void member(long group, long user, String role, LocalDateTime now) {
        jdbc.update(
                "INSERT INTO"
                    + " group_members(group_id,user_id,role,status,joined_at,created_at,updated_at)"
                    + " VALUES(?,?,?,'ACTIVE',?,?,?)",
                group,
                user,
                role,
                now,
                now,
                now);
    }

    private UUID original(
            long group, long creator, long payer, long account, long amount, LocalDateTime now) {
        UUID id = UUID.randomUUID(), self = UUID.randomUUID(), party = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO"
                    + " settlements(id,creator_id,title,total_amount,status,allocation_type,receiving_account_id,created_at,updated_at)"
                    + " VALUES(?,?,'original',?,'AWAITING_DEPOSIT','DUTCH_PAY',?,?,?)",
                id,
                creator,
                amount,
                account,
                now,
                now);
        jdbc.update(
                "INSERT INTO"
                    + " settlement_participants(id,settlement_id,source,linked_user_id,display_name,display_order,created_at,updated_at)"
                    + " VALUES(?,?,'REGISTERED_USER',?,'self',0,?,?)",
                self,
                id,
                creator,
                now,
                now);
        String name = jdbc.queryForObject("SELECT name FROM users WHERE id=?", String.class, payer);
        jdbc.update(
                "INSERT INTO"
                    + " settlement_participants(id,settlement_id,source,linked_user_id,source_group_id,display_name,display_order,created_at,updated_at)"
                    + " VALUES(?,?,'GROUP_MEMBER',?,?,?,1,?,?)",
                party,
                id,
                payer,
                group,
                name,
                now,
                now);
        jdbc.update(
                "INSERT INTO"
                    + " settlement_receiving_account_snapshots(settlement_id,source_account_id,organization,account_number_ciphertext,account_holder,created_at)"
                    + " VALUES(?,?,'0088',?,'receiver',?)",
                id,
                account,
                cipher.encryptSettlementAccountNumber(id, number(creator)),
                now);
        jdbc.update(
                "INSERT INTO"
                    + " settlement_shares(id,settlement_id,payer_participant_id,amount,created_at)"
                    + " VALUES(?,?,?,?,?)",
                UUID.randomUUID(),
                id,
                party,
                amount,
                now);
        // Real Dutch-pay requests also persist the creator's own allocation. It is not a payable
        // debt.
        jdbc.update(
                "INSERT INTO"
                    + " settlement_shares(id,settlement_id,payer_participant_id,amount,created_at)"
                    + " VALUES(?,?,?,?,?)",
                UUID.randomUUID(),
                id,
                self,
                1000,
                now);
        jdbc.update("UPDATE settlements SET total_amount=total_amount+1000 WHERE id=?", id);
        return id;
    }
}
