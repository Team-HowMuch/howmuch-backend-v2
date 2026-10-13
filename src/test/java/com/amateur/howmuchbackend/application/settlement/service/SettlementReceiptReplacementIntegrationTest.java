package com.amateur.howmuchbackend.application.settlement.service;

import com.amateur.howmuchbackend.application.auth.model.CurrentActor;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.application.port.in.settlement.command.CreateSettlementReceiptExpenseCommand;
import com.amateur.howmuchbackend.application.port.in.settlement.command.ReplaceSettlementReceiptExpenseCommand;
import com.amateur.howmuchbackend.adapter.out.persistence.settlement.*;
import com.amateur.howmuchbackend.adapter.in.web.settlement.SettlementReceiptExpenseController;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.common.exception.GlobalExceptionHandler;
import com.amateur.howmuchbackend.domain.settlement.*;
import com.amateur.howmuchbackend.domain.settlement.status.SettlementStatusCode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@DisplayName("중단 정산 영수증 교체 HTTP·PostgreSQL 통합")
class SettlementReceiptReplacementIntegrationTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 1, 10, 0);
    @MockitoBean private CurrentActorPort currentActorPort;
    @Autowired private SettlementReceiptReplacementService service;
    @Autowired private SettlementReceiptExpenseController controller;
    @Autowired private SettlementExpensePersistenceAdapter expenses;
    @MockitoSpyBean private SettlementItemPersistenceAdapter items;
    @Autowired private SettlementItemAllocationPersistenceAdapter allocations;
    @Autowired private SettlementSharePersistenceAdapter shares;
    @Autowired private SettlementPersistenceAdapter settlements;
    @Autowired private JdbcTemplate jdbcTemplate;
    private Long creatorId;
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
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        http = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator).build();
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM settlement_deposits WHERE settlement_id = ?", settlementId);
        jdbcTemplate.update("DELETE FROM settlements WHERE id = ?", settlementId);
        jdbcTemplate.update("DELETE FROM account_transactions WHERE account_id = ?", accountId);
        jdbcTemplate.update("DELETE FROM accounts WHERE id = ?", accountId);
        jdbcTemplate.update("DELETE FROM account_records WHERE id = ?", accountId);
        jdbcTemplate.update("DELETE FROM external_connections WHERE id = ?", connectionId);
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", creatorId);
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
                .allSatisfy(selection -> assertThat(selection.itemId()).isEqualTo(otherItem.id()));
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
                """)) {
            http.perform(put("/api/v2/settlements/{id}/expenses/receipt/{expense}", settlementId, receipt.id())
                    .contentType("application/json").content(body)).andExpect(status().isBadRequest());
        }
        assertUnchanged();
    }

    @Test
    void replaceReceipt_itemWriteFailure_rollsBackExpense() {
        doThrow(new IllegalStateException("synthetic item write failure")).when(items)
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
                """, Long.class, accountId, "d".repeat(64), NOW);
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
