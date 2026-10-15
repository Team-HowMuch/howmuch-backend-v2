package com.amateur.howmuchbackend.application.settlement.service;

import com.amateur.howmuchbackend.application.port.out.commoncode.LoadCommonCodePort;
import com.amateur.howmuchbackend.application.port.out.settlement.ClassifySettlementCategoryPort;
import com.amateur.howmuchbackend.application.port.out.settlement.ClassifySettlementCategoryPort.Outcome;
import com.amateur.howmuchbackend.application.port.out.settlement.ClassifySettlementCategoryPort.Result;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementCategoryRulePort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementExpensePort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementItemPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementPort;
import com.amateur.howmuchbackend.application.port.out.settlement.SettlementCategoryJobStorePort;
import com.amateur.howmuchbackend.application.settlement.policy.SettlementCategoryClassificationPolicy;
import com.amateur.howmuchbackend.domain.commoncode.CommonCode;
import com.amateur.howmuchbackend.domain.settlement.Settlement;
import com.amateur.howmuchbackend.domain.settlement.SettlementAllocationType;
import com.amateur.howmuchbackend.domain.settlement.SettlementCategoryFailureCode;
import com.amateur.howmuchbackend.domain.settlement.SettlementCategoryJob;
import com.amateur.howmuchbackend.domain.settlement.SettlementCategoryRule;
import com.amateur.howmuchbackend.domain.settlement.SettlementCategorySource;
import com.amateur.howmuchbackend.domain.settlement.SettlementExpense;
import com.amateur.howmuchbackend.domain.settlement.SettlementExpenseSource;
import com.amateur.howmuchbackend.domain.settlement.SettlementItem;
import com.amateur.howmuchbackend.domain.settlement.SettlementStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("정산 카테고리 분류 작업 처리")
class SettlementCategoryClassificationWorkerTest {
    private static final UUID SETTLEMENT_ID = UUID.randomUUID();
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 15, 3, 0);

    @Mock private SettlementCategoryJobStorePort jobs;
    @Mock private LoadSettlementPort settlements;
    @Mock private LoadSettlementExpensePort expenses;
    @Mock private LoadSettlementItemPort items;
    @Mock private LoadSettlementCategoryRulePort rules;
    @Mock private LoadCommonCodePort commonCodes;
    @Mock private ClassifySettlementCategoryPort classifier;

    private SettlementCategoryClassificationWorker worker;

    @BeforeEach
    void setUp() {
        worker = new SettlementCategoryClassificationWorker(jobs, settlements, expenses, items, rules, commonCodes,
                classifier, new SettlementCategoryClassificationPolicy(),
                Clock.fixed(NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC));
        when(settlements.findById(SETTLEMENT_ID)).thenReturn(Optional.of(settlement()));
        when(items.findAllBySettlementId(SETTLEMENT_ID)).thenReturn(List.of());
        when(rules.findActive()).thenReturn(List.of(new SettlementCategoryRule(1, "스타벅스", "CAFE_SNACK", 0)));
        when(commonCodes.findActiveByGroupCode("SETTLEMENT_CATEGORY")).thenReturn(List.of(
                code("FOOD", 1, "Y"), code("CAFE_SNACK", 2, "Y"), code("UNCATEGORIZED", 18, "Y"),
                code("TRANSFER", 19, "N")));
        when(classifier.available()).thenReturn(true);
        when(jobs.classify(any(), any(), any(), any())).thenReturn(true);
    }

    @Test
    @DisplayName("규칙이 맞으면 규칙 카테고리로 저장하고 AI를 부르지 않는다")
    void rule_savesWithoutAi() {
        var claim = claim(1);
        merchant("스타벅스 강남점");

        process(claim);

        verify(jobs).classify(claim, "CAFE_SNACK", SettlementCategorySource.RULE, NOW);
        verify(classifier, never()).classify(any());
    }

    @Test
    @DisplayName("규칙이 없으면 AI가 고른 정산용 카테고리로 저장한다")
    void ai_savesSettlementCode() {
        var claim = claim(1);
        merchant("행복분식");
        when(items.findAllBySettlementId(SETTLEMENT_ID)).thenReturn(List.of());
        when(classifier.classify(any())).thenReturn(Result.classified("FOOD"));

        process(claim);

        verify(jobs).classify(claim, "FOOD", SettlementCategorySource.AI, NOW);
        ArgumentCaptor<ClassifySettlementCategoryPort.Request> request =
                ArgumentCaptor.forClass(ClassifySettlementCategoryPort.Request.class);
        verify(classifier).classify(request.capture());
        assertThat(request.getValue().merchantNames()).containsExactly("행복분식");
        assertThat(request.getValue().candidates()).extracting(ClassifySettlementCategoryPort.Candidate::code)
                .containsExactly("FOOD", "CAFE_SNACK");
    }

    @Test
    @DisplayName("AI가 목록 밖 코드나 모르겠다고 답하면 미분류로 저장한다")
    void aiOutsideListOrUnknown_savesUncategorized() {
        merchant("행복상회");
        var outside = claim(1);
        when(classifier.classify(any())).thenReturn(Result.classified("TRANSFER"));
        process(outside);
        verify(jobs).classify(outside, "UNCATEGORIZED", SettlementCategorySource.UNCATEGORIZED, NOW);

        var unknown = claim(1);
        when(classifier.classify(any())).thenReturn(Result.of(Outcome.UNKNOWN));
        process(unknown);
        verify(jobs).classify(unknown, "UNCATEGORIZED", SettlementCategorySource.UNCATEGORIZED, NOW);
    }

    @Test
    @DisplayName("LLM이 설정되지 않았으면 규칙만 쓰고 바로 미분류로 저장한다")
    void aiUnavailable_savesUncategorized() {
        var claim = claim(1);
        merchant("행복상회");
        when(classifier.available()).thenReturn(false);

        process(claim);

        verify(jobs).classify(claim, "UNCATEGORIZED", SettlementCategorySource.UNCATEGORIZED, NOW);
        verify(classifier, never()).classify(any());
    }

    @Test
    @DisplayName("AI 호출이 실패하면 시도가 남은 동안 재시도로 돌린다")
    void aiFailure_retriesWithFailureCode() {
        merchant("행복상회");
        var provider = claim(1);
        when(classifier.classify(any())).thenReturn(Result.of(Outcome.PROVIDER_ERROR));
        process(provider);
        verify(jobs).retry(provider, NOW.plusMinutes(1), SettlementCategoryFailureCode.PROVIDER_ERROR, NOW);

        var invalid = claim(2);
        when(classifier.classify(any())).thenReturn(Result.of(Outcome.INVALID_RESPONSE));
        process(invalid);
        verify(jobs).retry(invalid, NOW.plusMinutes(5), SettlementCategoryFailureCode.INVALID_RESPONSE, NOW);
        verify(jobs, never()).classify(any(), any(), any(), any());
    }

    @Test
    @DisplayName("마지막 시도에서도 실패하면 실패 사유와 함께 미분류로 끝낸다")
    void lastAttemptFailure_givesUp() {
        var claim = claim(3);
        merchant("행복상회");
        when(classifier.classify(any())).thenReturn(Result.of(Outcome.PROVIDER_ERROR));

        process(claim);

        verify(jobs).giveUp(claim, SettlementCategoryFailureCode.PROVIDER_ERROR, NOW);
        verify(jobs, never()).retry(any(), any(), any(), any());
    }

    @Test
    @DisplayName("처리 중 예외가 나도 작업을 재시도로 돌리고 다음 작업으로 넘어간다")
    void unexpectedException_retries() {
        var claim = claim(1);
        when(expenses.findAllBySettlementId(SETTLEMENT_ID)).thenThrow(new IllegalStateException("db"));

        process(claim);

        verify(jobs).retry(claim, NOW.plusMinutes(1), SettlementCategoryFailureCode.PROVIDER_ERROR, NOW);
    }

    @Test
    @DisplayName("정산이 지워졌으면 AI를 부르지 않고 작업을 지운다")
    void deletedSettlement_discardsJob() {
        var claim = claim(1);
        when(settlements.findById(SETTLEMENT_ID)).thenReturn(Optional.empty());

        process(claim);

        verify(jobs).discard(claim, NOW);
        verifyNoInteractions(classifier, expenses, items);
    }

    @Test
    @DisplayName("분류 재료가 없으면(요청 뒤 지출이 직접 입력만 남음) 미분류로 저장한다")
    void noMaterial_savesUncategorized() {
        var claim = claim(1);
        when(expenses.findAllBySettlementId(SETTLEMENT_ID)).thenReturn(List.of(new SettlementExpense(
                UUID.randomUUID(), SETTLEMENT_ID, SettlementExpenseSource.MANUAL, null, null,
                "치킨", 1000, 0, NOW, NOW)));

        process(claim);

        verify(jobs).classify(claim, "UNCATEGORIZED", SettlementCategorySource.UNCATEGORIZED, NOW);
        verify(classifier, never()).classify(any());
    }

    @Test
    @DisplayName("가게 이름에 규칙이 없으면 품목명 규칙으로 분류한다")
    void itemRule_savesRule() {
        var claim = claim(1);
        SettlementExpense receipt = merchant("행복상회");
        when(items.findAllBySettlementId(SETTLEMENT_ID)).thenReturn(List.of(new SettlementItem(
                UUID.randomUUID(), SETTLEMENT_ID, receipt.id(), "스타벅스 기프티콘", 1, 1000, 0, NOW, NOW)));

        process(claim);

        verify(jobs).classify(claim, "CAFE_SNACK", SettlementCategorySource.RULE, NOW);
    }

    @Test
    @DisplayName("한 번에 정한 개수까지만 처리하고, 대기 작업이 없으면 멈춘다")
    void processDue_stopsAtLimitOrEmpty() {
        merchant("스타벅스");
        when(jobs.claimDue(eq(NOW), eq(NOW.plus(SettlementCategoryClassificationPolicy.LEASE))))
                .thenReturn(Optional.of(claim(1)), Optional.of(claim(1)), Optional.empty());

        assertThat(worker.processDue(5)).isEqualTo(2);
        assertThatThrownBy(() -> worker.processDue(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> worker.processDue(101)).isInstanceOf(IllegalArgumentException.class);
    }

    private void process(SettlementCategoryJob claim) {
        when(jobs.claimDue(any(), any())).thenReturn(Optional.of(claim), Optional.empty());
        assertThat(worker.processDue(1)).isEqualTo(1);
    }

    private SettlementExpense merchant(String name) {
        SettlementExpense expense = new SettlementExpense(UUID.randomUUID(), SETTLEMENT_ID,
                SettlementExpenseSource.RECEIPT_OCR, "receipt-1", null, name, 1000, 0, NOW, NOW);
        when(expenses.findAllBySettlementId(SETTLEMENT_ID)).thenReturn(List.of(expense));
        return expense;
    }

    private static SettlementCategoryJob claim(int attempt) {
        return new SettlementCategoryJob(SETTLEMENT_ID, attempt, 3, UUID.randomUUID(), NOW.plusMinutes(2));
    }

    private static Settlement settlement() {
        return new Settlement(SETTLEMENT_ID, 1L, "정산", 1000, SettlementAllocationType.DUTCH_PAY,
                SettlementStatus.AWAITING_DEPOSIT, NOW, NOW);
    }

    private static CommonCode code(String code, int order, String settlementUse) {
        return new CommonCode("SETTLEMENT_CATEGORY", code, code, null, order, "icon", settlementUse, null, true);
    }
}
