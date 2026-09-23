package com.amateur.howmuchbackend.application.treasurer.service;

import com.amateur.howmuchbackend.application.port.in.treasurer.command.AskTreasurerCommand;
import com.amateur.howmuchbackend.application.auth.model.CurrentActor;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.application.port.out.treasurer.GenerateTreasurerAnswerPort;
import com.amateur.howmuchbackend.application.port.out.treasurer.SaveTreasurerAnswerPort;
import com.amateur.howmuchbackend.domain.treasurer.SavedTreasurerAnswer;
import com.amateur.howmuchbackend.domain.treasurer.TreasurerAnswerGeneratedBy;
import com.amateur.howmuchbackend.application.treasurer.model.TreasurerAnswerDraft;
import com.amateur.howmuchbackend.application.treasurer.policy.InquiryParser;
import com.amateur.howmuchbackend.application.treasurer.policy.InquiryScopePolicy;
import com.amateur.howmuchbackend.application.treasurer.policy.TitleHintVerification;
import com.amateur.howmuchbackend.application.treasurer.policy.TreasurerAnswerFactCheck;
import com.amateur.howmuchbackend.application.treasurer.policy.TreasurerCardCopy;
import com.amateur.howmuchbackend.domain.settlement.SettlementAllocationType;
import com.amateur.howmuchbackend.domain.settlement.SettlementStatus;
import com.amateur.howmuchbackend.domain.treasurer.InquiryIntent;
import com.amateur.howmuchbackend.domain.treasurer.InquiryPeriod;
import com.amateur.howmuchbackend.domain.treasurer.PersonalSettlement;
import com.amateur.howmuchbackend.domain.treasurer.SettlementDirection;
import com.amateur.howmuchbackend.domain.treasurer.TreasurerAnswerView;
import com.amateur.howmuchbackend.domain.treasurer.TreasurerCardMarker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AskTreasurerServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-26T03:00:00Z");

    @Mock
    private TreasurerSettlementQuery treasurerSettlementQuery;

    @Mock
    private GenerateTreasurerAnswerPort generateTreasurerAnswerPort;

    @Mock
    private CurrentActorPort currentActorPort;

    @Mock
    private SaveTreasurerAnswerPort saveTreasurerAnswerPort;

    @Test
    void openSendSettlements_currentMonth_returned() {
        UUID id = UUID.fromString("11111111-1111-1111-1111-111111111111");
        when(treasurerSettlementQuery.loadMine()).thenReturn(List.of(
                new PersonalSettlement(
                        id,
                        "여행 모임",
                        SettlementDirection.SEND,
                        SettlementStatus.REQUESTED,
                        LocalDateTime.of(2026, 8, 10, 0, 0),
                        45_000L,
                        "요청자"
                ),
                new PersonalSettlement(
                        UUID.randomUUID(),
                        "여행 모임",
                        SettlementDirection.SEND,
                        SettlementStatus.COMPLETED,
                        LocalDateTime.of(2026, 8, 2, 0, 0),
                        9_000L,
                        "요청자"
                )
        ));
        when(generateTreasurerAnswerPort.generate(any()))
                .thenAnswer(invocation -> Optional.of(invocation.getArgument(0, TreasurerAnswerDraft.class)
                        .fallbackAnswer()));
        AskTreasurerService service = service();

        var result = service.ask(new AskTreasurerCommand("이번 달 내가 보낼 정산은 얼마야?"));

        assertThat(result.intent()).isEqualTo(InquiryIntent.SEND_SETTLEMENTS);
        assertThat(result.answer()).contains("1건").contains("45,000원");
        assertThat(result.items()).extracting(item -> item.settlementId()).containsExactly(id);
        assertThat(result.scope()).contains("이번 달");
        assertThat(result.view()).isEqualTo(TreasurerAnswerView.LIST);
    }

    @Test
    void usesLlmPhrasingForKnownIntents() {
        when(treasurerSettlementQuery.loadMine()).thenReturn(List.of(
                new PersonalSettlement(
                        UUID.randomUUID(),
                        "회식 정산",
                        SettlementDirection.SEND,
                        SettlementStatus.REQUESTED,
                        LocalDateTime.of(2026, 8, 10, 0, 0),
                        12_000L,
                        "요청자"
                )
        ));
        when(generateTreasurerAnswerPort.generate(any()))
                .thenReturn(Optional.of("이번 달 보낼 정산이 1건 남아 있어요. 12,000원입니다."));
        AskTreasurerService service = service();

        var result = service.ask(new AskTreasurerCommand("이번 달 내가 보낼 정산은 얼마야?"));

        assertThat(result.answer()).isEqualTo("이번 달 보낼 정산이 1건 남아 있어요. 12,000원입니다.");
    }

    @Test
    void fallsBackWhenLlmInventedAmounts() {
        when(treasurerSettlementQuery.loadMine()).thenReturn(List.of(
                new PersonalSettlement(
                        UUID.randomUUID(),
                        "회식 정산",
                        SettlementDirection.SEND,
                        SettlementStatus.REQUESTED,
                        LocalDateTime.of(2026, 8, 10, 0, 0),
                        12_000L,
                        "요청자"
                )
        ));
        when(generateTreasurerAnswerPort.generate(any()))
                .thenReturn(Optional.of("보낼 정산이 3건, 99,000원이에요."));
        AskTreasurerService service = service();

        var result = service.ask(new AskTreasurerCommand("이번 달 내가 보낼 정산은 얼마야?"));

        assertThat(result.answer()).contains("1건").contains("12,000원");
    }

    @Test
    void excludesReceiveSettlementsFromExpenseSummary() {
        when(treasurerSettlementQuery.loadMine()).thenReturn(List.of(
                new PersonalSettlement(
                        UUID.randomUUID(),
                        "회식 정산",
                        SettlementDirection.SEND,
                        SettlementStatus.REQUESTED,
                        LocalDateTime.of(2026, 8, 10, 0, 0),
                        12_000L,
                        "요청자"
                ),
                new PersonalSettlement(
                        UUID.randomUUID(),
                        "회식 정산",
                        SettlementDirection.RECEIVE,
                        SettlementStatus.REQUESTED,
                        LocalDateTime.of(2026, 8, 10, 0, 0),
                        80_000L,
                        "나"
                )
        ));
        when(generateTreasurerAnswerPort.generate(any()))
                .thenAnswer(invocation -> Optional.of(invocation.getArgument(0, TreasurerAnswerDraft.class)
                        .fallbackAnswer()));
        AskTreasurerService service = service();

        var result = service.ask(new AskTreasurerCommand("이번 달 지출을 요약해줘"));

        assertThat(result.intent()).isEqualTo(InquiryIntent.EXPENSE_SUMMARY);
        assertThat(result.answer()).contains("1건").contains("12,000원");
        assertThat(result.items()).extracting(item -> item.direction()).containsExactly(SettlementDirection.SEND);
    }

    @Test
    void keepsTitleFilterWhenPeriodAndGroupNameAppearTogether() {
        UUID travelId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        when(treasurerSettlementQuery.loadMine()).thenReturn(List.of(
                new PersonalSettlement(
                        travelId,
                        "여행 모임",
                        SettlementDirection.RECEIVE,
                        SettlementStatus.REQUESTED,
                        LocalDateTime.of(2026, 8, 10, 0, 0),
                        30_000L,
                        "나"
                ),
                new PersonalSettlement(
                        UUID.randomUUID(),
                        "회식 정산",
                        SettlementDirection.RECEIVE,
                        SettlementStatus.REQUESTED,
                        LocalDateTime.of(2026, 8, 10, 0, 0),
                        80_000L,
                        "나"
                )
        ));
        when(generateTreasurerAnswerPort.generate(any()))
                .thenAnswer(invocation -> Optional.of(invocation.getArgument(0, TreasurerAnswerDraft.class)
                        .fallbackAnswer()));
        AskTreasurerService service = service();

        var result = service.ask(new AskTreasurerCommand("이번 달 여행 모임에서 받을 돈이 있어?"));

        assertThat(result.intent()).isEqualTo(InquiryIntent.RECEIVE_SETTLEMENTS);
        assertThat(result.items()).extracting(item -> item.settlementId()).containsExactly(travelId);
        assertThat(result.scope()).contains("여행모임");
    }

    @Test
    void answersFromAllSettlementsWhenTitleMatchesNothing() {
        UUID dinnerId = UUID.fromString("22222222-2222-2222-2222-222222222233");
        when(treasurerSettlementQuery.loadMine()).thenReturn(List.of(
                new PersonalSettlement(
                        dinnerId,
                        "회식 정산",
                        SettlementDirection.SEND,
                        SettlementStatus.REQUESTED,
                        LocalDateTime.of(2026, 8, 10, 0, 0),
                        12_000L,
                        "요청자"
                )
        ));
        AskTreasurerService service = service();

        var result = service.ask(new AskTreasurerCommand("이번 달 여행 모임에서 보낼 정산 얼마야?"));

        assertThat(result.intent()).isEqualTo(InquiryIntent.SEND_SETTLEMENTS);
        assertThat(result.items()).extracting(item -> item.settlementId()).containsExactly(dinnerId);
        assertThat(result.scope()).contains("여행모임").contains("찾지 못해");
        assertThat(result.answer()).doesNotContain("보낼 정산은 없어요");
        // 질문은 없는 모임을 지목했는데 답변 재료는 다른 정산 금액이다.
        // LLM 이 그 금액을 그 모임 것으로 써도 FactCheck 는 숫자만 보므로 못 막는다.
        verifyNoInteractions(generateTreasurerAnswerPort);
    }

    @Test
    void narrowsByTitleWithoutGenericSuffix() {
        UUID travelId = UUID.fromString("22222222-2222-2222-2222-222222222244");
        when(treasurerSettlementQuery.loadMine()).thenReturn(List.of(
                new PersonalSettlement(
                        travelId,
                        "여행",
                        SettlementDirection.SEND,
                        SettlementStatus.REQUESTED,
                        LocalDateTime.of(2026, 8, 10, 0, 0),
                        40_000L,
                        "요청자"
                ),
                new PersonalSettlement(
                        UUID.randomUUID(),
                        "회식 정산",
                        SettlementDirection.SEND,
                        SettlementStatus.REQUESTED,
                        LocalDateTime.of(2026, 8, 10, 0, 0),
                        12_000L,
                        "요청자"
                )
        ));
        when(generateTreasurerAnswerPort.generate(any()))
                .thenAnswer(invocation -> Optional.of(invocation.getArgument(0, TreasurerAnswerDraft.class)
                        .fallbackAnswer()));
        AskTreasurerService service = service();

        var result = service.ask(new AskTreasurerCommand("이번 달 여행 모임에서 보낼 정산 얼마야?"));

        assertThat(result.items()).extracting(item -> item.settlementId()).containsExactly(travelId);
        assertThat(result.scope()).contains("'여행'").doesNotContain("찾지 못해");
        assertThat(result.answer()).contains("40,000원").doesNotContain("52,000원");
    }

    @Test
    void doesNotNarrowWhenGenericHintHappensToMatchATitle() {
        when(treasurerSettlementQuery.loadMine()).thenReturn(List.of(
                new PersonalSettlement(
                        UUID.randomUUID(),
                        "8월 모임 정산",
                        SettlementDirection.SEND,
                        SettlementStatus.REQUESTED,
                        LocalDateTime.of(2026, 8, 10, 0, 0),
                        52_000L,
                        "요청자"
                ),
                new PersonalSettlement(
                        UUID.randomUUID(),
                        "여행 정산",
                        SettlementDirection.SEND,
                        SettlementStatus.REQUESTED,
                        LocalDateTime.of(2026, 8, 10, 0, 0),
                        30_000L,
                        "요청자"
                ),
                new PersonalSettlement(
                        UUID.randomUUID(),
                        "점심 회비",
                        SettlementDirection.SEND,
                        SettlementStatus.REQUESTED,
                        LocalDateTime.of(2026, 8, 10, 0, 0),
                        29_000L,
                        "요청자"
                )
        ));
        AskTreasurerService service = service();

        var result = service.ask(new AskTreasurerCommand("모임 정산 얼마 보내야 해?"));

        // 힌트 "모임정산"이 "8월 모임 정산"에 그대로 맞는다. 일반어 판정을 맞춰보기 전에
        // 하면 그 정산 하나로 좁혀져 111,000원을 52,000원이라고 단정한다.
        assertThat(result.items()).hasSize(3);
        assertThat(result.answer()).contains("111,000원").doesNotContain("52,000원");
        // 좁히지 않았다는 사실을 사용자에게 알린다. 찾았다고 말하면 안 된다.
        assertThat(result.scope()).contains("찾지 못해").doesNotContain("찾았어요");
        verifyNoInteractions(generateTreasurerAnswerPort);
    }

    @Test
    void narrowsWhenHintCarriesANameBesidesGenericWords() {
        UUID travelId = UUID.randomUUID();
        when(treasurerSettlementQuery.loadMine()).thenReturn(List.of(
                new PersonalSettlement(
                        travelId,
                        "여행 정산",
                        SettlementDirection.SEND,
                        SettlementStatus.REQUESTED,
                        LocalDateTime.of(2026, 8, 10, 0, 0),
                        30_000L,
                        "요청자"
                ),
                new PersonalSettlement(
                        UUID.randomUUID(),
                        "점심 회비",
                        SettlementDirection.SEND,
                        SettlementStatus.REQUESTED,
                        LocalDateTime.of(2026, 8, 10, 0, 0),
                        29_000L,
                        "요청자"
                )
        ));
        when(generateTreasurerAnswerPort.generate(any()))
                .thenAnswer(invocation -> Optional.of(invocation.getArgument(0, TreasurerAnswerDraft.class)
                        .fallbackAnswer()));
        AskTreasurerService service = service();

        var result = service.ask(new AskTreasurerCommand("여행 정산 얼마 보내야 해?"));

        // 일반어를 걷어내도 "여행"이 남는다. 이름을 말한 것이므로 좁히는 것이 맞다.
        assertThat(result.items()).extracting(item -> item.settlementId()).containsExactly(travelId);
        assertThat(result.answer()).contains("30,000원").doesNotContain("59,000원");
    }

    @Test
    void doesNotNarrowByGenericWordLeftAfterStrippingSuffix() {
        when(treasurerSettlementQuery.loadMine()).thenReturn(List.of(
                new PersonalSettlement(
                        UUID.randomUUID(),
                        "제주도 여행 모임",
                        SettlementDirection.SEND,
                        SettlementStatus.REQUESTED,
                        LocalDateTime.of(2026, 8, 10, 0, 0),
                        99_000L,
                        "요청자"
                ),
                new PersonalSettlement(
                        UUID.randomUUID(),
                        "회식 정산",
                        SettlementDirection.SEND,
                        SettlementStatus.REQUESTED,
                        LocalDateTime.of(2026, 8, 10, 0, 0),
                        12_000L,
                        "요청자"
                )
        ));
        AskTreasurerService service = service();

        var result = service.ask(new AskTreasurerCommand("모임 정산 얼마 보내야 해?"));

        // "모임 정산"은 모임 이름이 아니라 일반어다. 뗀 형태 "모임"으로 좁히면
        // 회식 정산이 빠진 채 99,000원이라고 단정하게 된다.
        assertThat(result.items()).hasSize(2);
        assertThat(result.answer()).contains("111,000원");
        assertThat(result.scope()).doesNotContain("'모임'");
        verifyNoInteractions(generateTreasurerAnswerPort);
    }

    @Test
    void doesNotLetLlmPhraseStatusAnswerWhenTitleWasDropped() {
        when(treasurerSettlementQuery.loadMine()).thenReturn(List.of(
                new PersonalSettlement(
                        UUID.randomUUID(),
                        "회식 정산",
                        SettlementDirection.SEND,
                        SettlementStatus.REQUESTED,
                        LocalDateTime.of(2026, 8, 10, 0, 0),
                        12_000L,
                        "요청자"
                )
        ));
        AskTreasurerService service = service();

        var result = service.ask(new AskTreasurerCommand("이번 달 여행 모임 정산 상태 알려줘"));

        assertThat(result.intent()).isEqualTo(InquiryIntent.SETTLEMENT_STATUS);
        assertThat(result.scope()).contains("찾지 못해");
        verifyNoInteractions(generateTreasurerAnswerPort);
    }

    @Test
    void doesNotLetLlmPhraseDueAnswerWhenTitleWasDropped() {
        when(treasurerSettlementQuery.loadMine()).thenReturn(List.of(
                new PersonalSettlement(
                        UUID.randomUUID(),
                        "회식 정산",
                        SettlementDirection.SEND,
                        SettlementStatus.REQUESTED,
                        LocalDateTime.of(2026, 8, 25, 0, 0),
                        12_000L,
                        "요청자"
                )
        ));
        AskTreasurerService service = service();

        var result = service.ask(new AskTreasurerCommand("이번 주 여행 모임 마감 알려줘"));

        assertThat(result.intent()).isEqualTo(InquiryIntent.DUE_SETTLEMENTS);
        assertThat(result.scope()).contains("찾지 못해");
        verifyNoInteractions(generateTreasurerAnswerPort);
    }

    @Test
    void keepsAnswerTruthfulWhenUserHasNoSettlementAtAll() {
        when(treasurerSettlementQuery.loadMine()).thenReturn(List.of());
        AskTreasurerService service = service();

        var result = service.ask(new AskTreasurerCommand("이번 달 여행 모임에서 보낼 정산 얼마야?"));

        assertThat(result.items()).isEmpty();
        assertThat(result.answer()).contains("보낼 정산은 없어요");
        assertThat(result.scope()).contains("여행모임").contains("찾지 못해");
        verifyNoInteractions(generateTreasurerAnswerPort);
    }

    @Test
    void keepsTitleFilterWhenTitleExistsOutsideRequestedPeriod() {
        when(treasurerSettlementQuery.loadMine()).thenReturn(List.of(
                new PersonalSettlement(
                        UUID.randomUUID(),
                        "여행 모임",
                        SettlementDirection.SEND,
                        SettlementStatus.REQUESTED,
                        LocalDateTime.of(2026, 7, 10, 0, 0),
                        12_000L,
                        "요청자"
                )
        ));
        when(generateTreasurerAnswerPort.generate(any()))
                .thenAnswer(invocation -> Optional.of(invocation.getArgument(0, TreasurerAnswerDraft.class)
                        .fallbackAnswer()));
        AskTreasurerService service = service();

        var result = service.ask(new AskTreasurerCommand("이번 달 여행 모임에서 보낼 정산 얼마야?"));

        assertThat(result.items()).isEmpty();
        assertThat(result.scope()).contains("여행모임").contains("좁혀서 조회했어요");
        assertThat(result.scope()).doesNotContain("찾지 못해");
        assertThat(result.answer()).doesNotContain("찾았어요");
    }

    @Test
    void excludesDraftFromReceiveAnswers() {
        when(treasurerSettlementQuery.loadMine()).thenReturn(List.of(
                new PersonalSettlement(
                        UUID.randomUUID(),
                        "작성 중",
                        SettlementDirection.RECEIVE,
                        SettlementStatus.DRAFT,
                        LocalDateTime.of(2026, 8, 10, 0, 0),
                        80_000L,
                        "나"
                )
        ));
        when(generateTreasurerAnswerPort.generate(any()))
                .thenAnswer(invocation -> Optional.of(invocation.getArgument(0, TreasurerAnswerDraft.class)
                        .fallbackAnswer()));
        AskTreasurerService service = service();

        var result = service.ask(new AskTreasurerCommand("이번 달 받을 돈이 있어?"));

        assertThat(result.intent()).isEqualTo(InquiryIntent.RECEIVE_SETTLEMENTS);
        assertThat(result.answer()).contains("없어요");
        assertThat(result.items()).isEmpty();
    }

    @Test
    void infersThisMonthWhenPeriodIsOmitted() {
        when(treasurerSettlementQuery.loadMine()).thenReturn(List.of(
                new PersonalSettlement(
                        UUID.randomUUID(),
                        "회식 정산",
                        SettlementDirection.SEND,
                        SettlementStatus.REQUESTED,
                        LocalDateTime.of(2026, 8, 10, 0, 0),
                        12_000L,
                        "요청자"
                )
        ));
        when(generateTreasurerAnswerPort.generate(any()))
                .thenAnswer(invocation -> Optional.of(invocation.getArgument(0, TreasurerAnswerDraft.class)
                        .fallbackAnswer()));
        AskTreasurerService service = service();

        var result = service.ask(new AskTreasurerCommand("내가 보낼 정산은 얼마야?"));

        assertThat(result.period()).isEqualTo(InquiryPeriod.THIS_MONTH);
        assertThat(result.scope()).contains("이번 달 기준으로 조회했어요.");
    }

    @Test
    void answersLastMonthSendSettlements() {
        UUID lastMonthId = UUID.fromString("33333333-3333-3333-3333-333333333333");
        when(treasurerSettlementQuery.loadMine()).thenReturn(List.of(
                new PersonalSettlement(
                        lastMonthId,
                        "7월 회식",
                        SettlementDirection.SEND,
                        SettlementStatus.REQUESTED,
                        LocalDateTime.of(2026, 7, 20, 0, 0),
                        8_000L,
                        "요청자"
                ),
                new PersonalSettlement(
                        UUID.randomUUID(),
                        "8월 회식",
                        SettlementDirection.SEND,
                        SettlementStatus.REQUESTED,
                        LocalDateTime.of(2026, 8, 10, 0, 0),
                        12_000L,
                        "요청자"
                )
        ));
        when(generateTreasurerAnswerPort.generate(any()))
                .thenAnswer(invocation -> Optional.of(invocation.getArgument(0, TreasurerAnswerDraft.class)
                        .fallbackAnswer()));
        AskTreasurerService service = service();

        var result = service.ask(new AskTreasurerCommand("지난달 보낼 정산은 얼마야?"));

        assertThat(result.period()).isEqualTo(InquiryPeriod.LAST_MONTH);
        assertThat(result.items()).extracting(item -> item.settlementId()).containsExactly(lastMonthId);
        assertThat(result.answer()).contains("8,000원");
    }

    @Test
    void answersSettlementStatusWithOpenAndCompletedCounts() {
        when(treasurerSettlementQuery.loadMine()).thenReturn(List.of(
                new PersonalSettlement(
                        UUID.randomUUID(),
                        "진행",
                        SettlementDirection.SEND,
                        SettlementStatus.REQUESTED,
                        LocalDateTime.of(2026, 8, 10, 0, 0),
                        12_000L,
                        "요청자"
                ),
                new PersonalSettlement(
                        UUID.randomUUID(),
                        "완료",
                        SettlementDirection.SEND,
                        SettlementStatus.COMPLETED,
                        LocalDateTime.of(2026, 8, 2, 0, 0),
                        9_000L,
                        "요청자"
                )
        ));
        when(generateTreasurerAnswerPort.generate(any()))
                .thenAnswer(invocation -> Optional.of(invocation.getArgument(0, TreasurerAnswerDraft.class)
                        .fallbackAnswer()));
        AskTreasurerService service = service();

        var result = service.ask(new AskTreasurerCommand("이번 달 정산 진행 상황은?"));

        assertThat(result.intent()).isEqualTo(InquiryIntent.SETTLEMENT_STATUS);
        assertThat(result.view()).isEqualTo(TreasurerAnswerView.SUMMARY);
        assertThat(result.answer()).contains("아직 처리할 정산이 1건").contains("낼 정산 1건");
        assertThat(result.summary().send().value()).isEqualTo("12,000원");
        assertThat(result.summary().completed().value()).isEqualTo("1건");
    }

    @Test
    void includesCompletedSendInExpenseAndExcludesDraft() {
        when(treasurerSettlementQuery.loadMine()).thenReturn(List.of(
                new PersonalSettlement(
                        UUID.randomUUID(),
                        "끝난 지출",
                        SettlementDirection.SEND,
                        SettlementStatus.COMPLETED,
                        LocalDateTime.of(2026, 8, 2, 0, 0),
                        9_000L,
                        "요청자"
                ),
                new PersonalSettlement(
                        UUID.randomUUID(),
                        "작성 중",
                        SettlementDirection.SEND,
                        SettlementStatus.DRAFT,
                        LocalDateTime.of(2026, 8, 10, 0, 0),
                        80_000L,
                        "요청자"
                )
        ));
        when(generateTreasurerAnswerPort.generate(any()))
                .thenAnswer(invocation -> Optional.of(invocation.getArgument(0, TreasurerAnswerDraft.class)
                        .fallbackAnswer()));
        AskTreasurerService service = service();

        var result = service.ask(new AskTreasurerCommand("이번 달 지출을 요약해줘"));

        assertThat(result.answer()).contains("1건").contains("9,000원");
        assertThat(result.items()).extracting(item -> item.headline()).containsExactly("9,000원 지출");
    }

    @Test
    void returnsExamplesWhenIntentIsUnknown() {
        AskTreasurerService service = service();

        var result = service.ask(new AskTreasurerCommand("푸딩은 어떻게 만드나요?"));

        assertThat(result.intent()).isEqualTo(InquiryIntent.UNKNOWN);
        assertThat(result.view()).isEqualTo(TreasurerAnswerView.TEXT);
        assertThat(result.answer()).contains("이렇게 물어볼 수 있어요");
        assertThat(result.prompts()).extracting(item -> item.question()).contains("이번 주에 정산 있어?");
        verifyNoInteractions(generateTreasurerAnswerPort);
        verifyNoInteractions(treasurerSettlementQuery);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "정산은 어떻게 하는 거야?",
            "정산이 뭐야?",
            "정산은 어디서 확인해?",
            "정산 기한이 지나면 어떻게 돼?",
            "이 서비스는 어떻게 사용하는 거야?"
    })
    void clarifiesServiceRelatedQuestionInsteadOfGuidingScope(String question) {
        AskTreasurerService service = service();

        var result = service.ask(new AskTreasurerCommand(question));

        assertThat(result.view()).isEqualTo(TreasurerAnswerView.TEXT);
        assertThat(result.answer()).contains("정산과 관련된 질문으로 이해했어요");
        assertThat(result.answer()).doesNotContain("이렇게 물어볼 수 있어요");
        assertThat(result.prompts()).extracting(item -> item.question()).contains("이번 주에 정산 있어?");
        verifyNoInteractions(generateTreasurerAnswerPort);
        verifyNoInteractions(treasurerSettlementQuery);
    }

    @ParameterizedTest
    @CsvSource({
            "내가 아직 돈 안 보낸 게 있어?, SEND",
            "누구한테 돈 줘야 해?, SEND",
            "여행에서 내가 부담해야 하는 금액이 있어?, SEND",
            "나한테 돈 들어올 거 있어?, RECEIVE",
            "회비 안 낸 사람 알려줘, RECEIVE"
    })
    void answersColloquialQuestionWithTheSettlementOfThatDirection(String question, SettlementDirection expected) {
        UUID sendId = UUID.randomUUID();
        UUID receiveId = UUID.randomUUID();
        when(treasurerSettlementQuery.loadMine()).thenReturn(List.of(
                new PersonalSettlement(sendId, "회식 정산", SettlementDirection.SEND, SettlementStatus.REQUESTED,
                        LocalDateTime.of(2026, 8, 10, 0, 0), 12_000L, "요청자"),
                new PersonalSettlement(receiveId, "여행 모임", SettlementDirection.RECEIVE, SettlementStatus.REQUESTED,
                        LocalDateTime.of(2026, 8, 10, 0, 0), 30_000L, "나")
        ));
        when(generateTreasurerAnswerPort.generate(any()))
                .thenAnswer(invocation -> Optional.of(invocation.getArgument(0, TreasurerAnswerDraft.class)
                        .fallbackAnswer()));
        AskTreasurerService service = service();

        var result = service.ask(new AskTreasurerCommand(question));

        assertThat(result.items()).extracting(item -> item.settlementId())
                .containsExactly(expected == SettlementDirection.SEND ? sendId : receiveId);
        assertThat(result.answer()).doesNotContain("정산과 관련된 질문으로 이해했어요");
    }

    @ParameterizedTest
    @CsvSource({
            "왜 아직 정산이 안 끝났어?, SETTLEMENT_STATUS",
            "내 정산 중에 뭐가 남았어?, SETTLEMENT_STATUS",
            "아직 안 낸 정산 있어?, SEND_SETTLEMENTS"
    })
    void answersColloquialSettlementQuestionInsteadOfClarifying(String question, InquiryIntent expected) {
        when(generateTreasurerAnswerPort.generate(any()))
                .thenAnswer(invocation -> Optional.of(invocation.getArgument(0, TreasurerAnswerDraft.class)
                        .fallbackAnswer()));
        AskTreasurerService service = service();

        var result = service.ask(new AskTreasurerCommand(question));

        assertThat(result.intent()).isEqualTo(expected);
        assertThat(result.answer()).doesNotContain("정산과 관련된 질문으로 이해했어요");
        assertThat(result.answer()).doesNotContain("이렇게 물어볼 수 있어요");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "푸딩 만드는 방법 알려줘.",
            "오늘 날씨 알려줘.",
            "Java 공부법 알려줘.",
            "영화 추천해줘.",
            "주식 추천해줘."
    })
    void guidesScopeForQuestionUnrelatedToService(String question) {
        AskTreasurerService service = service();

        var result = service.ask(new AskTreasurerCommand(question));

        assertThat(result.view()).isEqualTo(TreasurerAnswerView.TEXT);
        assertThat(result.answer()).contains("이렇게 물어볼 수 있어요");
        assertThat(result.answer()).doesNotContain("정산과 관련된 질문으로 이해했어요");
        verifyNoInteractions(generateTreasurerAnswerPort);
        verifyNoInteractions(treasurerSettlementQuery);
    }

    @Test
    void carriesAllocationTypeAndStatusOnAnswerItems() {
        UUID id = UUID.fromString("33333333-3333-3333-3333-333333333333");
        when(treasurerSettlementQuery.loadMine()).thenReturn(List.of(
                new PersonalSettlement(
                        id,
                        "회식 정산",
                        SettlementDirection.SEND,
                        SettlementStatus.AWAITING_DEPOSIT,
                        LocalDateTime.of(2026, 8, 10, 0, 0),
                        12_000L,
                        "요청자",
                        1,
                        0,
                        SettlementAllocationType.ITEM_ALLOCATION
                )
        ));
        when(generateTreasurerAnswerPort.generate(any()))
                .thenAnswer(invocation -> Optional.of(invocation.getArgument(0, TreasurerAnswerDraft.class)
                        .fallbackAnswer()));
        AskTreasurerService service = service();

        var result = service.ask(new AskTreasurerCommand("이번 달 내가 보낼 정산은 얼마야?"));

        assertThat(result.items()).singleElement().satisfies(item -> {
            assertThat(item.settlementId()).isEqualTo(id);
            assertThat(item.allocationType()).isEqualTo(SettlementAllocationType.ITEM_ALLOCATION);
            assertThat(item.status()).isEqualTo(SettlementStatus.AWAITING_DEPOSIT);
        });
    }

    @Test
    void answersQuickActionSendAsListCard() {
        when(treasurerSettlementQuery.loadMine()).thenReturn(List.of(
                new PersonalSettlement(
                        UUID.fromString("11111111-1111-1111-1111-111111111111"),
                        "여행 모임",
                        SettlementDirection.SEND,
                        SettlementStatus.REQUESTED,
                        LocalDateTime.of(2026, 8, 10, 0, 0),
                        200L,
                        "빵장"
                )
        ));
        when(generateTreasurerAnswerPort.generate(any()))
                .thenAnswer(invocation -> Optional.of(invocation.getArgument(0, TreasurerAnswerDraft.class)
                        .fallbackAnswer()));
        AskTreasurerService service = service();

        var result = service.ask(new AskTreasurerCommand("보낼 정산"));

        assertThat(result.view()).isEqualTo(TreasurerAnswerView.LIST);
        assertThat(result.scope()).isEqualTo("이번 달 기준으로 조회했어요.");
        assertThat(result.answer()).contains("남아 있어요");
        assertThat(result.footer()).isEqualTo("누르면 해당되는 정산 상세로 이동해요!");
        assertThat(result.items()).singleElement().satisfies(item -> {
            assertThat(item.headline()).isEqualTo("200원 내야 함");
            assertThat(item.subtitle()).isEqualTo("빵장 요청 - 미납");
            assertThat(item.requestedDate()).isEqualTo("2026.08.10");
        });
    }

    @Test
    void answersQuickActionReceiveAsListCard() {
        when(treasurerSettlementQuery.loadMine()).thenReturn(List.of(
                new PersonalSettlement(
                        UUID.fromString("44444444-4444-4444-4444-444444444444"),
                        "회식 정산",
                        SettlementDirection.RECEIVE,
                        SettlementStatus.AWAITING_DEPOSIT,
                        LocalDateTime.of(2026, 8, 13, 0, 0),
                        600L,
                        "나",
                        3,
                        1
                )
        ));
        when(generateTreasurerAnswerPort.generate(any()))
                .thenAnswer(invocation -> Optional.of(invocation.getArgument(0, TreasurerAnswerDraft.class)
                        .fallbackAnswer()));
        AskTreasurerService service = service();

        var result = service.ask(new AskTreasurerCommand("받을 정산"));

        assertThat(result.view()).isEqualTo(TreasurerAnswerView.LIST);
        assertThat(result.answer()).contains("1건").contains("600원").contains("남아 있어요");
        assertThat(result.items()).singleElement().satisfies(item -> {
            assertThat(item.headline()).isEqualTo("600원을 3명이서 정산 중");
            assertThat(item.subtitle()).isEqualTo("총 3명 중 1명 완료");
            assertThat(item.marker()).isEqualTo(TreasurerCardMarker.MORE);
        });
    }

    @Test
    void answersQuickActionStatusAsSummaryCard() {
        when(treasurerSettlementQuery.loadMine()).thenReturn(List.of(
                new PersonalSettlement(
                        UUID.randomUUID(),
                        "낼 정산",
                        SettlementDirection.SEND,
                        SettlementStatus.REQUESTED,
                        LocalDateTime.of(2026, 8, 10, 0, 0),
                        45_000L,
                        "빵장"
                ),
                new PersonalSettlement(
                        UUID.randomUUID(),
                        "받을 정산",
                        SettlementDirection.RECEIVE,
                        SettlementStatus.AWAITING_DEPOSIT,
                        LocalDateTime.of(2026, 8, 10, 0, 0),
                        45_000L,
                        "나",
                        5,
                        0
                )
        ));
        when(generateTreasurerAnswerPort.generate(any()))
                .thenAnswer(invocation -> Optional.of(invocation.getArgument(0, TreasurerAnswerDraft.class)
                        .fallbackAnswer()));
        AskTreasurerService service = service();

        var result = service.ask(new AskTreasurerCommand("정산 상태"));

        assertThat(result.view()).isEqualTo(TreasurerAnswerView.SUMMARY);
        assertThat(result.answer()).contains("아직 처리할 정산이 2건").contains("낼 정산 1건").contains("받을 정산 1건");
        assertThat(result.summary().send().label()).isEqualTo("내가 낼 돈");
        assertThat(result.summary().receive().label()).isEqualTo("내가 받을 돈");
        assertThat(result.summary().completed().label()).isEqualTo("완료된 정산");
    }

    @Test
    void answersOcrGuideWithoutCallingLlm() {
        AskTreasurerService service = service();

        var result = service.ask(new AskTreasurerCommand("OCR 정산은 어떻게 해?"));

        assertThat(result.intent()).isEqualTo(InquiryIntent.GUIDE);
        assertThat(result.answer()).contains("아직 준비 중");
        verifyNoInteractions(generateTreasurerAnswerPort);
        verifyNoInteractions(treasurerSettlementQuery);
    }

    @Test
    void answersDueSettlementsByDeadlineInThisWeek() {
        UUID dueThisWeekId = UUID.fromString("55555555-5555-5555-5555-555555555555");
        when(treasurerSettlementQuery.loadMine()).thenReturn(List.of(
                new PersonalSettlement(
                        dueThisWeekId,
                        "지난주 요청",
                        SettlementDirection.SEND,
                        SettlementStatus.REQUESTED,
                        LocalDateTime.of(2026, 8, 19, 0, 0),
                        15_000L,
                        "빵장"
                ),
                new PersonalSettlement(
                        UUID.randomUUID(),
                        "이번 주 요청",
                        SettlementDirection.SEND,
                        SettlementStatus.REQUESTED,
                        LocalDateTime.of(2026, 8, 25, 0, 0),
                        8_000L,
                        "빵장"
                )
        ));
        when(generateTreasurerAnswerPort.generate(any()))
                .thenAnswer(invocation -> Optional.of(invocation.getArgument(0, TreasurerAnswerDraft.class)
                        .fallbackAnswer()));
        AskTreasurerService service = service();

        var result = service.ask(new AskTreasurerCommand("이번 주에 마감해야 할 정산 알려줘"));

        assertThat(result.intent()).isEqualTo(InquiryIntent.DUE_SETTLEMENTS);
        assertThat(result.items()).extracting(item -> item.settlementId()).containsExactly(dueThisWeekId);
        assertThat(result.answer()).contains("15,000원");
    }

    @Test
    @DisplayName("LLM이 문장을 만들면 LLM으로, 서버 문구면 SERVER로 저장한다")
    void storesWhoGeneratedTheAnswer() {
        when(treasurerSettlementQuery.loadMine()).thenReturn(List.of());
        when(generateTreasurerAnswerPort.generate(any())).thenReturn(Optional.empty());
        AskTreasurerService service = service();

        service.ask(new AskTreasurerCommand("이번 달 보낼 정산 얼마야"));

        ArgumentCaptor<SavedTreasurerAnswer> captor = ArgumentCaptor.forClass(SavedTreasurerAnswer.class);
        verify(saveTreasurerAnswerPort).save(captor.capture());
        assertThat(captor.getValue().generatedBy()).isEqualTo(TreasurerAnswerGeneratedBy.SERVER);
        assertThat(captor.getValue().question()).isEqualTo("이번 달 보낼 정산 얼마야");
        assertThat(captor.getValue().userId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("사실 검증을 통과한 LLM 문장은 LLM으로 저장한다")
    void storesLlmAsGenerator() {
        when(treasurerSettlementQuery.loadMine()).thenReturn(List.of());
        when(generateTreasurerAnswerPort.generate(any()))
                .thenAnswer(invocation -> Optional.of(invocation.getArgument(0, TreasurerAnswerDraft.class)
                        .fallbackAnswer()));
        AskTreasurerService service = service();

        service.ask(new AskTreasurerCommand("이번 달 보낼 정산 얼마야"));

        ArgumentCaptor<SavedTreasurerAnswer> captor = ArgumentCaptor.forClass(SavedTreasurerAnswer.class);
        verify(saveTreasurerAnswerPort).save(captor.capture());
        assertThat(captor.getValue().generatedBy()).isEqualTo(TreasurerAnswerGeneratedBy.LLM);
    }

    @Test
    @DisplayName("의도를 못 알아들은 답변도 저장한다")
    void storesUnmatchedAnswerToo() {
        AskTreasurerService service = service();

        var result = service.ask(new AskTreasurerCommand("오늘 날씨 어때"));

        verify(saveTreasurerAnswerPort).save(any(SavedTreasurerAnswer.class));
        assertThat(result.answerId()).isNotNull();
    }

    @Test
    @DisplayName("저장이 실패해도 답변은 나가고 answerId만 비어 있다")
    void stillAnswersWhenStoringFails() {
        doThrow(new RuntimeException("저장 실패"))
                .when(saveTreasurerAnswerPort).save(any(SavedTreasurerAnswer.class));
        AskTreasurerService service = service();

        var result = service.ask(new AskTreasurerCommand("오늘 날씨 어때"));

        assertThat(result.answerId()).isNull();
        assertThat(result.answer()).isNotBlank();
    }

    private AskTreasurerService service() {
        // ask()는 답변을 저장하려고 현재 사용자를 읽는다. 스텁이 없으면 모든 테스트가
        // 저장 실패 경로를 타므로 lenient로 둔다. service()만 부르고 ask()를 부르지 않는
        // 테스트가 있어 strict stub이면 불필요 스텁으로 잡힌다.
        lenient().when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(1L));
        return new AskTreasurerService(
                treasurerSettlementQuery,
                new InquiryParser(),
                new InquiryScopePolicy(),
                new TitleHintVerification(),
                generateTreasurerAnswerPort,
                new TreasurerAnswerFactCheck(),
                new TreasurerCardCopy(),
                currentActorPort,
                saveTreasurerAnswerPort,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }
}
