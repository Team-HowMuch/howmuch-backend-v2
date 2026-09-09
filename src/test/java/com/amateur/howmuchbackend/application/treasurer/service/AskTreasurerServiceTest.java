package com.amateur.howmuchbackend.application.treasurer.service;

import com.amateur.howmuchbackend.application.port.in.treasurer.command.AskTreasurerCommand;
import com.amateur.howmuchbackend.application.port.out.treasurer.GenerateTreasurerAnswerPort;
import com.amateur.howmuchbackend.application.treasurer.model.TreasurerAnswerDraft;
import com.amateur.howmuchbackend.application.treasurer.policy.InquiryParser;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
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
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AskTreasurerServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-26T03:00:00Z");

    @Mock
    private TreasurerSettlementQuery treasurerSettlementQuery;

    @Mock
    private GenerateTreasurerAnswerPort generateTreasurerAnswerPort;

    @Test
    void answersOpenSendSettlementsInCurrentMonth() {
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

    private AskTreasurerService service() {
        return new AskTreasurerService(
                treasurerSettlementQuery,
                new InquiryParser(),
                generateTreasurerAnswerPort,
                new TreasurerAnswerFactCheck(),
                new TreasurerCardCopy(),
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }
}
