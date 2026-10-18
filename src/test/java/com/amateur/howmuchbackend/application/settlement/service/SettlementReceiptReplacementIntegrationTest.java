package com.amateur.howmuchbackend.application.settlement.service;

import com.amateur.howmuchbackend.application.auth.model.CurrentActor;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.application.port.in.settlement.command.CreateSettlementReceiptExpenseCommand;
import com.amateur.howmuchbackend.application.port.in.settlement.command.ReplaceSettlementReceiptExpenseCommand;
import com.amateur.howmuchbackend.adapter.out.persistence.settlement.SettlementExpensePersistenceAdapter;
import com.amateur.howmuchbackend.adapter.out.persistence.settlement.SettlementItemPersistenceAdapter;
import com.amateur.howmuchbackend.adapter.out.persistence.settlement.SettlementItemAllocationPersistenceAdapter;
import com.amateur.howmuchbackend.adapter.out.persistence.settlement.SettlementSharePersistenceAdapter;
import com.amateur.howmuchbackend.adapter.out.persistence.settlement.SettlementPersistenceAdapter;
import com.amateur.howmuchbackend.adapter.out.persistence.settlement.SettlementParticipantPersistenceAdapter;
import com.amateur.howmuchbackend.adapter.out.persistence.settlement.SettlementShareDeletionAdapter;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementSharePort;
import com.amateur.howmuchbackend.adapter.in.web.settlement.SettlementReceiptExpenseController;
import com.amateur.howmuchbackend.adapter.in.web.settlement.SettlementAllocationController;
import com.amateur.howmuchbackend.adapter.in.web.settlement.SettlementController;
import com.amateur.howmuchbackend.application.port.in.settlement.command.ReplaceSettlementDutchPayAllocationsCommand;
import com.amateur.howmuchbackend.application.port.in.settlement.command.UpdateSettlementDutchPayAllocationCommand;
import com.amateur.howmuchbackend.application.port.out.settlement.SettlementDutchPayAllocationStorePort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.common.exception.GlobalExceptionHandler;
import com.amateur.howmuchbackend.domain.settlement.SettlementExpense;
import com.amateur.howmuchbackend.domain.settlement.SettlementItem;
import com.amateur.howmuchbackend.domain.settlement.SettlementAllocationType;
import com.amateur.howmuchbackend.domain.settlement.SettlementStatus;
import com.amateur.howmuchbackend.domain.settlement.SettlementShare;
import com.amateur.howmuchbackend.domain.settlement.SettlementDutchPayAllocation;
import com.amateur.howmuchbackend.domain.settlement.SettlementItemSelection;
import com.amateur.howmuchbackend.domain.settlement.SettlementItemAllocationFraction;
import com.amateur.howmuchbackend.domain.settlement.SettlementItemSubmission;
import com.amateur.howmuchbackend.domain.settlement.status.SettlementStatusCode;
import com.amateur.howmuchbackend.support.SettlementDepositTestCleanup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@DisplayName("중단 정산 영수증·더치페이·참여자 이름 수정 HTTP·PostgreSQL 통합")
class SettlementReceiptReplacementIntegrationTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 1, 10, 0);
    @MockitoBean private CurrentActorPort currentActorPort;
    @MockitoBean(name = "utcDateTimeProvider") private DateTimeProvider dateTimeProvider;
    @Autowired private SettlementReceiptReplacementService service;
    @Autowired private SettlementReceiptExpenseController controller;
    @Autowired private SettlementAllocationController allocationController;
    @Autowired private SettlementController settlementController;
    @Autowired private SettlementAllocationService allocationService;
    @MockitoSpyBean private SettlementParticipantPersistenceAdapter participants;
    @Autowired private SettlementParticipantService participantService;
    @Autowired private SettlementService lifecycle;
    @Autowired private SettlementItemAllocationWriter itemWriter;
    @Autowired private SettlementDutchPayAllocationStorePort dutchPayStore;
    @Autowired private SettlementExpensePersistenceAdapter expenses;
    @MockitoSpyBean private SettlementItemPersistenceAdapter items;
    @Autowired private SettlementItemAllocationPersistenceAdapter allocations;
    @Autowired private SettlementSharePersistenceAdapter shares;
    @Autowired private LoadSettlementSharePort loadShares;
    @MockitoSpyBean private SettlementShareDeletionAdapter shareDeletion;
    @Autowired private SettlementPersistenceAdapter settlements;
    @Autowired private JdbcTemplate jdbcTemplate;
    private Long creatorId;
    private Long payerUserId;
    private Long connectionId;
    private Long accountId;
    private UUID settlementId;
    private UUID creatorParticipantId;
    private UUID payerParticipantId;
    private SettlementExpense receipt;
    private SettlementItem originalItem;
    private MockMvc http;

    @BeforeEach
    void setUp() {
        // OS 시계 정밀도와 무관하게 PostgreSQL 저장 정밀도를 넘는 감사 시각을 사용한다.
        when(dateTimeProvider.getNow()).thenReturn(Optional.of(NOW.withNano(123_456_789)));
        String key = UUID.randomUUID().toString();
        creatorId = insertUser(key);
        connectionId = insertConnection(creatorId, key);
        accountId = insertAccount(creatorId, connectionId, key);
        settlementId = UUID.randomUUID();
        creatorParticipantId = UUID.randomUUID();
        payerParticipantId = UUID.randomUUID();
        insertSettlement();
        insertParticipants();
        when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(creatorId));
        receipt = expenses.save(SettlementExpense.receiptOcr(settlementId, "original", "기존", 10_000,
                NOW, 0, NOW));
        originalItem = items.saveAll(List.of(SettlementItem.fromReceipt(settlementId, receipt.id(),
                "기존 품목", 1, 10_000, 0, NOW, 0,
                List.of(new SettlementItem.OptionInput("기존 옵션", 1_000))))).getFirst();
        // 저장 직후 객체에는 나노초가 남을 수 있으므로 DB에서 읽은 전체 필드를 보존 검증의 기준으로 삼는다.
        originalItem = items.findAllBySettlementId(settlementId).getFirst();
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        http = MockMvcBuilders.standaloneSetup(controller, allocationController, settlementController).setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator).build();
    }

    @AfterEach
    void cleanUp() {
        if (SettlementDepositTestCleanup.preserveSettlement(jdbcTemplate, settlementId)) {
            return;
        }
        SettlementDepositTestCleanup.deleteDepositsWhenMutable(jdbcTemplate, settlementId);
        jdbcTemplate.update("DELETE FROM settlements WHERE id = ?", settlementId);
        jdbcTemplate.update("DELETE FROM account_transactions WHERE account_id = ?", accountId);
        jdbcTemplate.update("DELETE FROM accounts WHERE id = ?", accountId);
        jdbcTemplate.update("DELETE FROM account_records WHERE id = ?", accountId);
        jdbcTemplate.update("DELETE FROM external_connections WHERE id = ?", connectionId);
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", creatorId);
        if (payerUserId != null) {
            jdbcTemplate.update("DELETE FROM users WHERE id = ?", payerUserId);
        }
    }

    @Test
    void replaceReceipt_repeatedPut_replacesWithoutDuplicates() throws Exception {
        for (int attempt = 0; attempt < 2; attempt++) {
            http.perform(put("/api/v2/settlements/{id}/expenses/receipt/{expense}", settlementId, receipt.id())
                    .contentType("application/json").content("""
                    {"storeName":"수정 상호","paidAt":"2026-10-12T12:30:00","discount":500,"deliveryFee":3000,
                     "items":[{"name":"수정 품목","quantity":2,"amount":12000,"discount":1000,
                     "options":[{"name":"새 옵션","price":2000}]}]}
                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.expense.expenseId").value(receipt.id().toString()))
                    .andExpect(jsonPath("$.data.totalExpenseAmount").value(13_500))
                    .andExpect(jsonPath("$.data.items[0].quantity").value(2));
        }
        var saved = expenses.findBySettlementIdAndId(settlementId, receipt.id()).orElseThrow();
        assertThat(saved.sourceReference()).isEqualTo("original");
        assertThat(saved.displayOrder()).isZero();
        assertThat(saved.description()).isEqualTo("수정 상호");
        assertThat(saved.paidAt()).isEqualTo(LocalDateTime.of(2026, 10, 12, 12, 30));
        assertThat(saved.discount()).isEqualTo(500);
        assertThat(saved.deliveryFee()).isEqualTo(3000);
        assertThat(expenses.findAllBySettlementId(settlementId)).hasSize(1);
        assertThat(items.findAllBySettlementId(settlementId)).hasSize(1)
                .allSatisfy(item -> assertThat(item.id()).isNotEqualTo(originalItem.id()));
        assertThat(count("settlement_item_options")).isEqualTo(1);
        assertThat(settlements.findById(settlementId).orElseThrow().status()).isEqualTo(SettlementStatus.SUSPENDED);
        assertThat(settlements.findById(settlementId).orElseThrow().totalAmount()).isEqualTo(13_500);
    }

    @Test
    void replaceReceipt_otherReceipt_preservesExpenseAndSelection() {
        var other = expenses.save(SettlementExpense.receiptOcr(settlementId, "other", "다른 영수증", 5000,
                NOW, 1, NOW));
        var otherItem = items.saveAll(List.of(SettlementItem.fromReceipt(settlementId, other.id(),
                "다른 품목", 1, 5000, 0, NOW))).getFirst();
        other = expenses.findBySettlementIdAndId(settlementId, other.id()).orElseThrow();
        UUID otherItemId = otherItem.id();
        otherItem = items.findAllBySettlementId(settlementId).stream()
                .filter(item -> item.id().equals(otherItemId)).findFirst().orElseThrow();
        allocations.saveSelection(SettlementItemSelection.create(settlementId, originalItem.id(),
                creatorParticipantId, SettlementItemAllocationFraction.ONE, 1, NOW));
        allocations.saveSelection(SettlementItemSelection.create(settlementId, otherItem.id(),
                payerParticipantId, SettlementItemAllocationFraction.ONE, 1, NOW));
        allocations.saveSubmission(SettlementItemSubmission.submit(settlementId, creatorParticipantId, NOW));
        allocations.saveSubmission(SettlementItemSubmission.submit(settlementId, payerParticipantId, NOW));
        insertSnapshot();
        shares.saveAll(List.of(SettlementShare.create(settlementId, payerParticipantId, 10_000, NOW)));
        service.replace(settlementId, receipt.id(), command(null));
        assertThat(expenses.findAllBySettlementId(settlementId)).hasSize(2).contains(other);
        assertThat(items.findAllBySettlementId(settlementId)).hasSize(2).contains(otherItem);
        assertThat(allocations.findAllSelections(settlementId)).hasSize(1)
                .allSatisfy(selection -> assertThat(selection.itemId()).isEqualTo(otherItemId));
        assertThat(allocations.findAllSubmissions(settlementId)).isEmpty();
        assertThat(count("settlement_shares")).isZero();
        assertThat(expenses.findBySettlementIdAndId(settlementId, receipt.id()).orElseThrow().paidAt()).isNull();
        assertThat(settlements.findById(settlementId).orElseThrow().totalAmount()).isEqualTo(17_000);
    }

    @Test
    void replaceReceipt_draft_preservesAllocationAndStatus() {
        jdbcTemplate.update("UPDATE settlements SET status = 'DRAFT' WHERE id = ?", settlementId);
        service.replace(settlementId, receipt.id(), command(NOW));
        var saved = settlements.findById(settlementId).orElseThrow();
        assertThat(saved.status()).isEqualTo(SettlementStatus.DRAFT);
        assertThat(saved.allocationType()).isEqualTo(SettlementAllocationType.ITEM_ALLOCATION);
    }

    @ParameterizedTest
    @EnumSource(value = SettlementStatus.class, names = {"REQUESTED", "AWAITING_DEPOSIT", "COMPLETED"})
    void replaceReceipt_unsupportedStatus_rejected(SettlementStatus state) {
        jdbcTemplate.update("UPDATE settlements SET status = ? WHERE id = ?", state.name(), settlementId);
        assertError(() -> service.replace(settlementId, receipt.id(), command(NOW)), SettlementStatusCode.SETTLEMENT_NOT_EDITABLE);
        assertUnchanged();
    }

    @Test
    void replaceReceipt_foreignActor_rejected() {
        when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(creatorId + 1000));
        assertError(() -> service.replace(settlementId, receipt.id(), command(NOW)), SettlementStatusCode.SETTLEMENT_ACCESS_DENIED);
        assertUnchanged();
    }

    @Test
    void replaceReceipt_foreignExpense_rejected() {
        assertError(() -> service.replace(settlementId, UUID.randomUUID(), command(NOW)), SettlementStatusCode.SETTLEMENT_EXPENSE_NOT_FOUND);
        assertError(() -> service.replace(UUID.randomUUID(), receipt.id(), command(NOW)), SettlementStatusCode.SETTLEMENT_NOT_FOUND);
        assertUnchanged();
    }

    @Test
    void replaceReceipt_manualExpense_rejected() {
        var manual = expenses.save(SettlementExpense.manual(settlementId, "수동", 5000, 1, NOW));
        assertError(() -> service.replace(settlementId, manual.id(), command(NOW)), SettlementStatusCode.SETTLEMENT_RECEIPT_REQUIRED);
        assertUnchanged();
    }

    @Test
    void replaceReceipt_invalidBody_rejectedBeforeWrites() throws Exception {
        for (String body : List.of(
                """
                {"storeName":"수정","items":[]}
                """,
                """
                {"storeName":"수정","deliveryFee":-1,"items":[{"name":"품목","quantity":1,"amount":100}]}
                """,
                """
                {"storeName":"수정","discount":100,"items":[{"name":"품목","quantity":1,"amount":100}]}
                """,
                """
                {"storeName":"수정","items":[{"name":"품목","quantity":0,"amount":100}]}
                """,
                """
                {"storeName":"수정","items":[{"name":"품목","quantity":1,"amount":100,"discount":101}]}
                """)) {
            http.perform(put("/api/v2/settlements/{id}/expenses/receipt/{expense}", settlementId, receipt.id())
                    .contentType("application/json").content(body)).andExpect(status().isBadRequest());
        }
        assertUnchanged();
    }

    @Test
    void replaceReceipt_itemWriteFailure_rollsBackExpense() {
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new IllegalStateException("synthetic failure after item replacement");
        }).when(items)
                .replaceByExpenseId(eq(settlementId), eq(receipt.id()), anyList());
        assertThatThrownBy(() -> service.replace(settlementId, receipt.id(), command(NOW)))
                .isInstanceOf(IllegalStateException.class);
        assertUnchanged();
        assertThat(settlements.findById(settlementId).orElseThrow().totalAmount()).isEqualTo(10_000);
    }

    @Test
    void replaceReceipt_confirmedDeposit_rejectedAndPreserved() {
        insertDeposit();
        assertError(() -> service.replace(settlementId, receipt.id(), command(NOW)), SettlementStatusCode.SETTLEMENT_REVISION_PAYMENT_EXISTS);
        assertUnchanged();
        assertThat(count("settlement_deposits")).isEqualTo(1);
        assertThat(count("settlement_shares")).isEqualTo(1);
    }

    @Test
    void createReceipt_suspended_stillRejected() throws Exception {
        http.perform(post("/api/v2/settlements/{id}/expenses/receipt", settlementId)
                .contentType("application/json").content("""
                {"receiptReference":"new","storeName":"신규","items":[{"name":"품목","quantity":1,"amount":1000}]}
                """)).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("SETTLEMENT_NOT_EDITABLE"));
        assertUnchanged();
    }

    @Test
    void replaceReceipt_concurrentRequests_serializedWithoutDuplicates() throws Exception {
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var start = new java.util.concurrent.CountDownLatch(1);
            var tasks = java.util.stream.IntStream.range(0, 2).mapToObj(index -> executor.submit(() -> {
                start.await();
                return service.replace(settlementId, receipt.id(), command(NOW));
            })).toList();
            start.countDown();
            for (var task : tasks) task.get(20, java.util.concurrent.TimeUnit.SECONDS);
        }
        assertThat(expenses.findAllBySettlementId(settlementId)).hasSize(1);
        assertThat(items.findAllBySettlementId(settlementId)).hasSize(1);
        assertThat(settlements.findById(settlementId).orElseThrow().totalAmount()).isEqualTo(12_000);
    }

    @Test
    void dutchPay_suspendedPatchAndPut_preserveStatusAndRebuildOnResume() throws Exception {
        initializeDutchPay();
        shares.saveAll(List.of(SettlementShare.create(settlementId, payerParticipantId, 6000, NOW)));
        http.perform(get("/api/v2/settlements/{id}/dutch-pay-allocations", settlementId))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.balanced").value(true));
        assertThat(count("settlement_shares")).isEqualTo(1);
        http.perform(patch("/api/v2/settlements/{id}/dutch-pay-allocations/{participant}",
                settlementId, creatorParticipantId).contentType("application/json").content("{\"amount\":3000}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.balanced").value(false));
        assertThat(count("settlement_shares")).isZero();
        assertThat(settlements.findById(settlementId).orElseThrow().status()).isEqualTo(SettlementStatus.SUSPENDED);
        assertError(() -> lifecycle.resume(settlementId), SettlementStatusCode.SETTLEMENT_SHARE_TOTAL_MISMATCH);
        assertThat(count("settlement_shares")).isZero();
        http.perform(put("/api/v2/settlements/{id}/dutch-pay-allocations", settlementId)
                .contentType("application/json").content("""
                {"allocations":[{"participantId":"%s","amount":3000},{"participantId":"%s","amount":7000}]}
                """.formatted(creatorParticipantId, payerParticipantId)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.balanced").value(true));
        assertThat(settlements.findById(settlementId).orElseThrow().status()).isEqualTo(SettlementStatus.SUSPENDED);
        http.perform(post("/api/v2/settlements/{id}/resume", settlementId)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("AWAITING_DEPOSIT"));
        assertThat(jdbcTemplate.queryForList("SELECT amount FROM settlement_shares WHERE settlement_id = ? ORDER BY amount", Long.class, settlementId))
                .containsExactly(3000L, 7000L);
    }

    @Test
    void dutchPay_receiptTotalChanged_requiresNewAllocationBeforeResume() {
        initializeDutchPay();
        service.replace(settlementId, receipt.id(), command(NOW));
        assertError(() -> lifecycle.resume(settlementId), SettlementStatusCode.SETTLEMENT_SHARE_TOTAL_MISMATCH);
        allocationService.replace(settlementId, dutchCommand(5000, 7000));
        assertThat(lifecycle.resume(settlementId).status()).isEqualTo(SettlementStatus.AWAITING_DEPOSIT);
        assertThat(jdbcTemplate.queryForList("SELECT amount FROM settlement_shares WHERE settlement_id = ? ORDER BY amount", Long.class, settlementId))
                .containsExactly(5000L, 7000L);
    }

    @Test
    void dutchPay_invalidReplacement_keepsAmountsAndShares() {
        initializeDutchPay();
        shares.saveAll(List.of(SettlementShare.create(settlementId, payerParticipantId, 6000, NOW)));
        assertError(() -> allocationService.replace(settlementId, dutchCommand(5000, 7000)), SettlementStatusCode.SETTLEMENT_DUTCH_PAY_TOTAL_MISMATCH);
        assertThat(allocationService.get(settlementId).balanced()).isTrue();
        assertThat(count("settlement_shares")).isEqualTo(1);
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"0,10000", "10000,0"})
    void dutchPay_zeroAmount_resumeCreatesOnlyPositiveShare(long creatorAmount, long payerAmount) {
        initializeDutchPay();
        allocationService.replace(settlementId, dutchCommand(creatorAmount, payerAmount));
        lifecycle.resume(settlementId);
        assertThat(settlements.findById(settlementId).orElseThrow().status()).isEqualTo(SettlementStatus.AWAITING_DEPOSIT);
        assertThat(loadShares.findAllBySettlementId(settlementId)).singleElement().satisfies(share -> {
            assertThat(share.amount()).isEqualTo(10_000);
            assertThat(share.payerParticipantId()).isEqualTo(
                    creatorAmount > 0 ? creatorParticipantId : payerParticipantId);
        });
        assertThat(count("settlement_participants")).isEqualTo(2);
    }

    @Test
    void dutchPay_confirmedDeposit_mutationsAndResumeRejected() {
        initializeDutchPay();
        // initializeDutchPay가 이미 snapshot을 저장했으므로 입금 fixture는 동일 snapshot을 재사용한다.
        jdbcTemplate.update("DELETE FROM settlement_receiving_account_snapshots WHERE settlement_id = ?", settlementId);
        insertDeposit();
        assertError(() -> allocationService.update(settlementId, creatorParticipantId,
                new UpdateSettlementDutchPayAllocationCommand(3000)), SettlementStatusCode.SETTLEMENT_REVISION_PAYMENT_EXISTS);
        assertError(() -> allocationService.replace(settlementId, dutchCommand(3000, 7000)), SettlementStatusCode.SETTLEMENT_REVISION_PAYMENT_EXISTS);
        assertError(() -> lifecycle.resume(settlementId), SettlementStatusCode.SETTLEMENT_REVISION_PAYMENT_EXISTS);
        assertThat(allocationService.get(settlementId).balanced()).isTrue();
        assertThat(count("settlement_deposits")).isEqualTo(1);
        assertThat(count("settlement_shares")).isEqualTo(1);
    }

    @Test
    void dutchPay_foreignActor_rejected() {
        initializeDutchPay();
        when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(creatorId + 1000));
        assertError(() -> allocationService.get(settlementId), SettlementStatusCode.SETTLEMENT_ACCESS_DENIED);
        assertError(() -> allocationService.update(settlementId, creatorParticipantId,
                new UpdateSettlementDutchPayAllocationCommand(3000)), SettlementStatusCode.SETTLEMENT_ACCESS_DENIED);
        assertError(() -> allocationService.replace(settlementId, dutchCommand(3000, 7000)), SettlementStatusCode.SETTLEMENT_ACCESS_DENIED);
    }

    @Test
    void itemAllocation_replacedReceipt_resumeAllowsSelectingAndSubmitting() {
        service.replace(settlementId, receipt.id(), command(NOW));
        insertSnapshot();
        assertThat(lifecycle.resume(settlementId).status()).isEqualTo(SettlementStatus.REQUESTED);
        assertThat(allocations.findAllSubmissions(settlementId)).isEmpty();
        UUID itemId = items.findAllBySettlementId(settlementId).getFirst().id();
        LocalDateTime selectedAt = LocalDateTime.now(java.time.ZoneOffset.UTC);
        var selection = new com.amateur.howmuchbackend.application.port.in.settlement.command.SaveSettlementItemSelectionCommand(
                SettlementItemAllocationFraction.ONE, 1);
        itemWriter.save(settlementId, creatorParticipantId, itemId, selection, selectedAt, SettlementItemAllocationEntry.CREATOR);
        itemWriter.save(settlementId, payerParticipantId, itemId, selection, selectedAt, SettlementItemAllocationEntry.LINK);
        itemWriter.submit(settlementId, creatorParticipantId, selectedAt, SettlementItemAllocationEntry.CREATOR);
        itemWriter.submit(settlementId, payerParticipantId, selectedAt, SettlementItemAllocationEntry.LINK);
        assertThat(settlements.findById(settlementId).orElseThrow().status()).isEqualTo(SettlementStatus.AWAITING_DEPOSIT);
        assertThat(jdbcTemplate.queryForList("SELECT amount FROM settlement_shares WHERE settlement_id = ?", Long.class, settlementId))
                .containsExactlyInAnyOrder(6000L, 6000L);
    }

    @ParameterizedTest
    @EnumSource(SettlementAllocationType.class)
    void rename_suspended_preservesIdentityAllocationsAndStatus(SettlementAllocationType type) throws Exception {
        if (type == SettlementAllocationType.DUTCH_PAY) {
            initializeDutchPay();
        } else {
            insertSnapshot();
        }
        allocations.saveSelection(SettlementItemSelection.create(settlementId, originalItem.id(),
                payerParticipantId, SettlementItemAllocationFraction.ONE, 1, NOW));
        allocations.saveSubmission(SettlementItemSubmission.submit(settlementId, payerParticipantId, NOW));
        shares.saveAll(List.of(SettlementShare.create(settlementId, payerParticipantId, 6000, NOW)));
        var beforeParticipant = participants.findBySettlementIdAndId(settlementId, payerParticipantId).orElseThrow();
        var beforeSettlement = settlements.findById(settlementId).orElseThrow();
        var beforeSelections = allocations.findAllSelections(settlementId);
        var beforeSubmissions = allocations.findAllSubmissions(settlementId);
        var beforeDutchPay = dutchPayStore.findAllBySettlementId(settlementId);
        var beforeShares = loadShares.findAllBySettlementId(settlementId);

        renameRequest(payerParticipantId, "수정 이름").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.participantId").value(payerParticipantId.toString()))
                .andExpect(jsonPath("$.data.displayName").value("수정 이름"));

        var saved = participants.findBySettlementIdAndId(settlementId, payerParticipantId).orElseThrow();
        assertThat(saved).usingRecursiveComparison().ignoringFields("displayName", "updatedAt")
                .isEqualTo(beforeParticipant);
        assertThat(saved.displayName()).isEqualTo("수정 이름");
        assertThat(settlements.findById(settlementId).orElseThrow()).isEqualTo(beforeSettlement);
        assertThat(allocations.findAllSelections(settlementId)).isEqualTo(beforeSelections);
        assertThat(allocations.findAllSubmissions(settlementId)).isEqualTo(beforeSubmissions);
        assertThat(dutchPayStore.findAllBySettlementId(settlementId)).isEqualTo(beforeDutchPay);
        assertThat(loadShares.findAllBySettlementId(settlementId)).isEqualTo(beforeShares);
        assertThat(lifecycle.resume(settlementId).status()).isEqualTo(
                type == SettlementAllocationType.DUTCH_PAY
                        ? SettlementStatus.AWAITING_DEPOSIT : SettlementStatus.REQUESTED);
        assertThat(participants.findBySettlementIdAndId(settlementId, payerParticipantId).orElseThrow().displayName())
                .isEqualTo("수정 이름");
    }

    @Test
    void rename_draft_preservesExistingFlow() throws Exception {
        jdbcTemplate.update("UPDATE settlements SET status = 'DRAFT' WHERE id = ?", settlementId);
        renameRequest(payerParticipantId, "수정 이름").andExpect(status().isOk());
        assertThat(settlements.findById(settlementId).orElseThrow().status()).isEqualTo(SettlementStatus.DRAFT);
    }

    @Test
    void rename_linkedParticipant_doesNotChangeUserName() throws Exception {
        payerUserId = insertUser(UUID.randomUUID().toString());
        jdbcTemplate.update("UPDATE settlement_participants SET source = 'REGISTERED_USER', linked_user_id = ?, phone_hash = NULL WHERE id = ?",
                payerUserId, payerParticipantId);
        String accountName = jdbcTemplate.queryForObject("SELECT name FROM users WHERE id = ?", String.class, payerUserId);
        renameRequest(payerParticipantId, "정산 별칭").andExpect(status().isOk());
        var saved = participants.findBySettlementIdAndId(settlementId, payerParticipantId).orElseThrow();
        assertThat(saved.linkedUserId()).isEqualTo(payerUserId);
        assertThat(saved.displayName()).isEqualTo("정산 별칭");
        assertThat(jdbcTemplate.queryForObject("SELECT name FROM users WHERE id = ?", String.class, payerUserId))
                .isEqualTo(accountName);
    }

    @ParameterizedTest
    @EnumSource(value = SettlementStatus.class, names = {"REQUESTED", "AWAITING_DEPOSIT", "COMPLETED"})
    void rename_runningOrCompleted_rejected(SettlementStatus state) throws Exception {
        jdbcTemplate.update("UPDATE settlements SET status = ? WHERE id = ?", state.name(), settlementId);
        var before = participants.findAllBySettlementId(settlementId);
        renameRequest(payerParticipantId, "수정 이름").andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("SETTLEMENT_NOT_EDITABLE"));
        assertThat(participants.findAllBySettlementId(settlementId)).isEqualTo(before);
    }

    @Test
    void rename_invalidNameOrParticipant_rejectedAndPreserved() throws Exception {
        var before = participants.findAllBySettlementId(settlementId);
        for (String name : List.of("", "   ", "가".repeat(101))) {
            renameRequest(payerParticipantId, name).andExpect(status().isBadRequest());
        }
        assertError(() -> participantService.rename(settlementId, payerParticipantId, " "),
                SettlementStatusCode.INVALID_SETTLEMENT_PARTICIPANT_DISPLAY_NAME);
        renameRequest(UUID.randomUUID(), "수정 이름").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("SETTLEMENT_PARTICIPANT_NOT_FOUND"));
        renameRequest(creatorParticipantId, "수정 이름").andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("SETTLEMENT_CREATOR_PARTICIPANT_REQUIRED"));
        assertThat(participants.findAllBySettlementId(settlementId)).isEqualTo(before);
    }

    @Test
    void rename_confirmedDeposit_rejectedAndPreserved() throws Exception {
        insertDeposit();
        var before = participants.findAllBySettlementId(settlementId);
        renameRequest(payerParticipantId, "수정 이름").andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("SETTLEMENT_REVISION_PAYMENT_EXISTS"));
        assertThat(participants.findAllBySettlementId(settlementId)).isEqualTo(before);
        assertThat(count("settlement_deposits")).isEqualTo(1);
        assertThat(count("settlement_shares")).isEqualTo(1);
    }

    @Test
    void rename_writeFailure_rollsBackParticipant() {
        var before = participants.findAllBySettlementId(settlementId);
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new IllegalStateException("synthetic failure after participant update");
        }).when(participants).update(any());
        assertThatThrownBy(() -> participantService.rename(settlementId, payerParticipantId, "수정 이름"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(participants.findAllBySettlementId(settlementId)).isEqualTo(before);
        assertThat(settlements.findById(settlementId).orElseThrow().status()).isEqualTo(SettlementStatus.SUSPENDED);
    }

    @Test
    void suspended_addAndRemoveParticipants_remainRejected() throws Exception {
        var before = participants.findAllBySettlementId(settlementId);
        http.perform(post("/api/v2/settlements/{id}/participants", settlementId)
                .contentType("application/json").content("{\"source\":\"CONTACT\",\"displayName\":\"신규\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("SETTLEMENT_NOT_EDITABLE"));
        assertError(() -> participantService.remove(settlementId, payerParticipantId),
                SettlementStatusCode.SETTLEMENT_NOT_EDITABLE);
        assertThat(participants.findAllBySettlementId(settlementId)).isEqualTo(before);
    }

    @Test
    void suspended_actualParticipant_cannotUseAnyEditApi() throws Exception {
        initializeDutchPay();
        payerUserId = insertUser(UUID.randomUUID().toString());
        jdbcTemplate.update("UPDATE settlement_participants SET source = 'REGISTERED_USER', linked_user_id = ?, phone_hash = NULL WHERE id = ?",
                payerUserId, payerParticipantId);
        var before = participants.findAllBySettlementId(settlementId);
        when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(payerUserId));
        renameRequest(payerParticipantId, "수정 이름").andExpect(status().isForbidden());
        http.perform(put("/api/v2/settlements/{id}/expenses/receipt/{expense}", settlementId, receipt.id())
                .contentType("application/json").content("""
                {"storeName":"수정","items":[{"name":"품목","quantity":1,"amount":1000}]}
                """)).andExpect(status().isForbidden());
        http.perform(put("/api/v2/settlements/{id}/dutch-pay-allocations", settlementId)
                .contentType("application/json").content(dutchBody(creatorParticipantId, 3000, payerParticipantId, 7000)))
                .andExpect(status().isForbidden());
        assertThat(participants.findAllBySettlementId(settlementId)).isEqualTo(before);
        assertUnchanged();
        assertThat(dutchPayStore.findAllBySettlementId(settlementId)).extracting(SettlementDutchPayAllocation::amount)
                .containsExactlyInAnyOrder(4000L, 6000L);
    }

    @ParameterizedTest
    @EnumSource(value = SettlementStatus.class, names = {"REQUESTED", "AWAITING_DEPOSIT", "COMPLETED"})
    void dutchPay_runningOrCompleted_rejected(SettlementStatus state) throws Exception {
        initializeDutchPay();
        jdbcTemplate.update("UPDATE settlements SET status = ? WHERE id = ?", state.name(), settlementId);
        var before = dutchPayStore.findAllBySettlementId(settlementId);
        http.perform(put("/api/v2/settlements/{id}/dutch-pay-allocations", settlementId)
                .contentType("application/json").content(dutchBody(creatorParticipantId, 3000, payerParticipantId, 7000)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("SETTLEMENT_NOT_EDITABLE"));
        assertThat(dutchPayStore.findAllBySettlementId(settlementId)).isEqualTo(before);
    }

    @Test
    void dutchPay_invalidIdsAndAmounts_rejectedWithoutPartialWrites() throws Exception {
        initializeDutchPay();
        shares.saveAll(List.of(SettlementShare.create(settlementId, payerParticipantId, 6000, NOW)));
        var before = dutchPayStore.findAllBySettlementId(settlementId);
        for (String body : List.of(
                dutchBody(creatorParticipantId, 3000, UUID.randomUUID(), 7000),
                dutchBody(creatorParticipantId, 3000, creatorParticipantId, 7000),
                dutchBody(creatorParticipantId, -1, payerParticipantId, 10_001),
                dutchBody(creatorParticipantId, 5000, payerParticipantId, 7000),
                "{\"allocations\":[]}")) {
            http.perform(put("/api/v2/settlements/{id}/dutch-pay-allocations", settlementId)
                    .contentType("application/json").content(body)).andExpect(status().is4xxClientError());
            assertThat(dutchPayStore.findAllBySettlementId(settlementId)).isEqualTo(before);
            assertThat(count("settlement_shares")).isEqualTo(1);
        }
    }

    @Test
    void dutchPay_lateWriteFailure_rollsBackAllAmountsAndShares() {
        initializeDutchPay();
        shares.saveAll(List.of(SettlementShare.create(settlementId, payerParticipantId, 6000, NOW)));
        var before = dutchPayStore.findAllBySettlementId(settlementId);
        var beforeShares = loadShares.findAllBySettlementId(settlementId);
        // 마지막 Share 초기화까지 진행한 뒤 실패시켜 전원 금액 변경도 롤백되는지 확인한다.
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new IllegalStateException("synthetic failure after share deletion");
        }).when(shareDeletion).deleteAllBySettlementId(settlementId);
        assertThatThrownBy(() -> allocationService.replace(settlementId, dutchCommand(3000, 7000)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(dutchPayStore.findAllBySettlementId(settlementId)).isEqualTo(before);
        assertThat(loadShares.findAllBySettlementId(settlementId)).isEqualTo(beforeShares);
        assertThat(settlements.findById(settlementId).orElseThrow().status()).isEqualTo(SettlementStatus.SUSPENDED);
    }

    private org.springframework.test.web.servlet.ResultActions renameRequest(UUID participantId, String name) throws Exception {
        return http.perform(patch("/api/v2/settlements/{id}/participants/{participant}", settlementId, participantId)
                .contentType("application/json").content("{\"displayName\":\"%s\"}".formatted(name)));
    }

    private String dutchBody(UUID firstId, long firstAmount, UUID secondId, long secondAmount) {
        return """
                {"allocations":[{"participantId":"%s","amount":%d},{"participantId":"%s","amount":%d}]}
                """.formatted(firstId, firstAmount, secondId, secondAmount);
    }

    private void initializeDutchPay() {
        jdbcTemplate.update("UPDATE settlements SET allocation_type = 'DUTCH_PAY' WHERE id = ?", settlementId);
        insertSnapshot();
        dutchPayStore.replaceAll(settlementId, List.of(
                SettlementDutchPayAllocation.create(settlementId, creatorParticipantId, 4000, NOW),
                SettlementDutchPayAllocation.create(settlementId, payerParticipantId, 6000, NOW)));
    }

    private ReplaceSettlementDutchPayAllocationsCommand dutchCommand(long creatorAmount, long payerAmount) {
        return new ReplaceSettlementDutchPayAllocationsCommand(List.of(
                new ReplaceSettlementDutchPayAllocationsCommand.Allocation(creatorParticipantId, creatorAmount),
                new ReplaceSettlementDutchPayAllocationsCommand.Allocation(payerParticipantId, payerAmount)));
    }

    private ReplaceSettlementReceiptExpenseCommand command(LocalDateTime paidAt) {
        return new ReplaceSettlementReceiptExpenseCommand("수정 상호", paidAt,
                List.of(new CreateSettlementReceiptExpenseCommand.Item("수정", 2, 12_000)), 0, 0);
    }

    private void assertUnchanged() {
        assertThat(expenses.findBySettlementIdAndId(settlementId, receipt.id()).orElseThrow().description()).isEqualTo("기존");
        assertThat(items.findAllBySettlementId(settlementId)).contains(originalItem);
        assertThat(count("settlement_item_options")).isEqualTo(1);
    }

    private void assertError(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, SettlementStatusCode code) {
        assertThatThrownBy(action).isInstanceOfSatisfying(ApplicationException.class,
                error -> assertThat(error.getStatusCode()).isEqualTo(code));
    }

    private int count(String table) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE settlement_id = ?", Integer.class, settlementId);
    }

    private void insertSnapshot() {
        jdbcTemplate.update("""
                INSERT INTO settlement_receiving_account_snapshots (settlement_id,source_account_id,organization,
                account_number_ciphertext,account_holder,created_at) VALUES (?,?,'0020','unused','생성자',?)
                """, settlementId, accountId, NOW);
    }

    private void insertDeposit() {
        insertSnapshot();
        UUID shareId = UUID.randomUUID();
        shares.saveAll(List.of(new SettlementShare(shareId, settlementId, payerParticipantId, 10_000, NOW)));
        Long transactionId = jdbcTemplate.queryForObject("""
                INSERT INTO account_transactions (account_id,external_transaction_key,transaction_type,amount,transacted_at)
                VALUES (?,?,'INCOME',1000,?) RETURNING id
                """, Long.class, accountId, UUID.randomUUID().toString().replace("-", "").repeat(2), NOW);
        jdbcTemplate.update("""
                INSERT INTO settlement_deposits (id,settlement_id,share_id,source_account_id,account_transaction_id,matched_at)
                VALUES (?,?,?,?,?,?)
                """, UUID.randomUUID(), settlementId, shareId, accountId, transactionId, NOW);
    }

    private Long insertUser(String uniqueKey) {
        return jdbcTemplate.queryForObject("""
                        INSERT INTO users (
                            identity_key, name, public_id, phone_number, pin_hash,
                            phone_verified, role, active, required_terms_agreed_at,
                            created_at, updated_at
                        )
                        VALUES (?, '최수아', ?, ?, 'not-used', TRUE, 'USER', TRUE, ?, ?, ?)
                        RETURNING id
                        """,
                Long.class,
                "request-" + uniqueKey,
                "request_" + uniqueKey.replace("-", "").substring(0, 12),
                "test-" + uniqueKey.substring(0, 12),
                NOW,
                NOW,
                NOW
        );
    }

    private Long insertConnection(Long userId, String uniqueKey) {
        return jdbcTemplate.queryForObject("""
                        INSERT INTO external_connections (
                            user_id, connected_id_ciphertext, configuration_fingerprint,
                            active, created_at, updated_at
                        )
                        VALUES (?, ?, ?, TRUE, ?, ?)
                        RETURNING id
                        """,
                Long.class,
                userId,
                "ciphertext-" + uniqueKey,
                "b".repeat(64),
                NOW,
                NOW
        );
    }

    private Long insertAccount(Long userId, Long externalConnectionId, String uniqueKey) {
        return jdbcTemplate.queryForObject("""
                        INSERT INTO accounts (
                            user_id, external_connection_id, organization, candidate_key,
                            external_account_key_ciphertext, type, name, masked_number,
                            balance, currency, active, product_code, is_default,
                            display_order, created_at, updated_at
                        )
                        VALUES (?, ?, '0020', ?, ?, 'DEPOSIT_TRUST', '생활비 통장',
                                '****6789', 1000000, 'KRW', TRUE, '11', TRUE, 0, ?, ?)
                        RETURNING id
                        """,
                Long.class,
                userId,
                externalConnectionId,
                "candidate-" + uniqueKey,
                "source-ciphertext-" + uniqueKey,
                NOW,
                NOW
        );
    }

    private void insertSettlement() {
        jdbcTemplate.update("""
                        INSERT INTO settlements (
                            id, creator_id, title, total_amount, allocation_type,
                            receiving_account_id, status, version, created_at, updated_at
                        )
                        VALUES (?, ?, '회식 정산', 10000, 'ITEM_ALLOCATION', ?, 'SUSPENDED', 0, ?, ?)
                        """,
                settlementId,
                creatorId,
                accountId,
                NOW,
                NOW
        );
    }

    private void insertParticipants() {
        jdbcTemplate.update("""
                        INSERT INTO settlement_participants (
                            id, settlement_id, source, linked_user_id, display_name,
                            display_order, created_at, updated_at
                        )
                        VALUES (?, ?, 'REGISTERED_USER', ?, '최수아', 0, ?, ?)
                        """,
                creatorParticipantId,
                settlementId,
                creatorId,
                NOW,
                NOW
        );
        jdbcTemplate.update("""
                        INSERT INTO settlement_participants (
                            id, settlement_id, source, phone_hash, display_name,
                            display_order, created_at, updated_at
                        )
                        VALUES (?, ?, 'CONTACT', ?, '민선영', 1, ?, ?)
                        """,
                payerParticipantId,
                settlementId,
                "c".repeat(64),
                NOW,
                NOW
        );
    }
}
