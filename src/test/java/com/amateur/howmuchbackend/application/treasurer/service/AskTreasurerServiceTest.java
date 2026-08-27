package com.amateur.howmuchbackend.application.treasurer.service;

import com.amateur.howmuchbackend.application.port.in.treasurer.command.AskTreasurerCommand;
import com.amateur.howmuchbackend.application.port.out.treasurer.GenerateTreasurerAnswerPort;
import com.amateur.howmuchbackend.application.treasurer.policy.InquiryParser;
import com.amateur.howmuchbackend.domain.settlement.SettlementStatus;
import com.amateur.howmuchbackend.domain.treasurer.InquiryIntent;
import com.amateur.howmuchbackend.domain.treasurer.PersonalSettlement;
import com.amateur.howmuchbackend.domain.treasurer.SettlementDirection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
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
                .thenAnswer(invocation -> invocation.getArgument(0, GenerateTreasurerAnswerPort.TreasurerAnswerDraft.class)
                        .fallbackAnswer());
        AskTreasurerService service = service();

        var result = service.ask(new AskTreasurerCommand("이번 달 내가 보낼 정산은 얼마야?"));

        assertThat(result.intent()).isEqualTo(InquiryIntent.SEND_SETTLEMENTS.name());
        assertThat(result.answer()).contains("1건").contains("45,000원");
        assertThat(result.highlights()).extracting(item -> item.settlementId()).containsExactly(id);
        assertThat(result.scope()).contains("이번 달");
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
        when(generateTreasurerAnswerPort.generate(any())).thenReturn("이번 달 보낼 정산이 1건 남아 있어요. 12,000원입니다.");
        AskTreasurerService service = service();

        var result = service.ask(new AskTreasurerCommand("이번 달 내가 보낼 정산은 얼마야?"));

        assertThat(result.answer()).isEqualTo("이번 달 보낼 정산이 1건 남아 있어요. 12,000원입니다.");
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
                .thenAnswer(invocation -> invocation.getArgument(0, GenerateTreasurerAnswerPort.TreasurerAnswerDraft.class)
                        .fallbackAnswer());
        AskTreasurerService service = service();

        var result = service.ask(new AskTreasurerCommand("이번 달 지출을 요약해줘"));

        assertThat(result.intent()).isEqualTo(InquiryIntent.EXPENSE_SUMMARY.name());
        assertThat(result.answer()).contains("1건").contains("12,000원");
        assertThat(result.highlights()).extracting(item -> item.direction()).containsExactly("SEND");
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
                .thenAnswer(invocation -> invocation.getArgument(0, GenerateTreasurerAnswerPort.TreasurerAnswerDraft.class)
                        .fallbackAnswer());
        AskTreasurerService service = service();

        var result = service.ask(new AskTreasurerCommand("이번 달 여행 모임에서 받을 돈이 있어?"));

        assertThat(result.intent()).isEqualTo(InquiryIntent.RECEIVE_SETTLEMENTS.name());
        assertThat(result.highlights()).extracting(item -> item.settlementId()).containsExactly(travelId);
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
                .thenAnswer(invocation -> invocation.getArgument(0, GenerateTreasurerAnswerPort.TreasurerAnswerDraft.class)
                        .fallbackAnswer());
        AskTreasurerService service = service();

        var result = service.ask(new AskTreasurerCommand("이번 달 받을 돈이 있어?"));

        assertThat(result.intent()).isEqualTo(InquiryIntent.RECEIVE_SETTLEMENTS.name());
        assertThat(result.answer()).contains("없어요");
        assertThat(result.highlights()).isEmpty();
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
                .thenAnswer(invocation -> invocation.getArgument(0, GenerateTreasurerAnswerPort.TreasurerAnswerDraft.class)
                        .fallbackAnswer());
        AskTreasurerService service = service();

        var result = service.ask(new AskTreasurerCommand("내가 보낼 정산은 얼마야?"));

        assertThat(result.period()).isEqualTo("THIS_MONTH");
        assertThat(result.scope()).contains("기간을 지정하지 않아 이번 달만 조회했어요.");
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
                .thenAnswer(invocation -> invocation.getArgument(0, GenerateTreasurerAnswerPort.TreasurerAnswerDraft.class)
                        .fallbackAnswer());
        AskTreasurerService service = service();

        var result = service.ask(new AskTreasurerCommand("지난달 보낼 정산은 얼마야?"));

        assertThat(result.period()).isEqualTo("LAST_MONTH");
        assertThat(result.highlights()).extracting(item -> item.settlementId()).containsExactly(lastMonthId);
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
                .thenAnswer(invocation -> invocation.getArgument(0, GenerateTreasurerAnswerPort.TreasurerAnswerDraft.class)
                        .fallbackAnswer());
        AskTreasurerService service = service();

        var result = service.ask(new AskTreasurerCommand("이번 달 정산 진행 상황은?"));

        assertThat(result.intent()).isEqualTo(InquiryIntent.SETTLEMENT_STATUS.name());
        assertThat(result.answer()).contains("진행 중인 정산 1건").contains("완료된 정산 1건");
        assertThat(result.highlights()).extracting(item -> item.title()).containsExactly("진행");
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
                .thenAnswer(invocation -> invocation.getArgument(0, GenerateTreasurerAnswerPort.TreasurerAnswerDraft.class)
                        .fallbackAnswer());
        AskTreasurerService service = service();

        var result = service.ask(new AskTreasurerCommand("이번 달 지출을 요약해줘"));

        assertThat(result.answer()).contains("1건").contains("9,000원");
        assertThat(result.highlights()).extracting(item -> item.title()).containsExactly("끝난 지출");
    }

    @Test
    void returnsExamplesWhenIntentIsUnknown() {
        AskTreasurerService service = service();

        var result = service.ask(new AskTreasurerCommand("푸딩은 어떻게 만드나요?"));

        assertThat(result.intent()).isEqualTo(InquiryIntent.UNKNOWN.name());
        assertThat(result.answer()).contains("이렇게 물어볼 수 있어요");
        verifyNoInteractions(generateTreasurerAnswerPort);
        verifyNoInteractions(treasurerSettlementQuery);
    }

    private AskTreasurerService service() {
        return new AskTreasurerService(
                treasurerSettlementQuery,
                new InquiryParser(),
                generateTreasurerAnswerPort,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }
}
