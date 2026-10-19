package com.amateur.howmuchbackend.application.treasurer.service;

import com.amateur.howmuchbackend.application.port.in.treasurer.command.AskTreasurerCommand;
import com.amateur.howmuchbackend.application.port.in.treasurer.result.TreasurerAnswerResult;
import com.amateur.howmuchbackend.application.treasurer.model.TreasurerGuideReply;
import com.amateur.howmuchbackend.application.auth.model.CurrentActor;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.application.port.out.treasurer.GenerateTreasurerAnswerPort;
import com.amateur.howmuchbackend.application.port.out.treasurer.GenerateTreasurerGuidePort;
import com.amateur.howmuchbackend.application.port.out.treasurer.SaveTreasurerAnswerPort;
import com.amateur.howmuchbackend.domain.treasurer.SavedTreasurerAnswer;
import com.amateur.howmuchbackend.domain.treasurer.TreasurerAnswerGeneratedBy;
import com.amateur.howmuchbackend.application.treasurer.model.TreasurerAnswerDraft;
import com.amateur.howmuchbackend.application.treasurer.policy.InquiryParser;
import com.amateur.howmuchbackend.application.treasurer.policy.InquiryScopePolicy;
import com.amateur.howmuchbackend.application.treasurer.policy.TreasurerGuideCheck;
import com.amateur.howmuchbackend.application.treasurer.model.TreasurerKnowledge;
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
import org.junit.jupiter.api.BeforeEach;
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

import static org.mockito.ArgumentMatchers.eq;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AskTreasurerServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-26T03:00:00Z");
    private static final TreasurerKnowledge KNOWLEDGE = TreasurerKnowledge.parse("""
            ## [PAY] 송금하기
            - [토스로 N원 보내기]를 누르면 토스가 열린다.
            ## [CREATE-AMOUNT] 금액 정하기
            - [영수증 인식]으로 영수증을 읽어 온다.
            ## [REMIND] 독촉 알림
            - 한 번 보내면 30분 동안 다시 보낼 수 없다.
            ## [NOT-YET] 아직 제공하지 않는 기능
            - 정기 정산
            """);

    @Mock
    private TreasurerSettlementQuery treasurerSettlementQuery;

    @Mock
    private GenerateTreasurerAnswerPort generateTreasurerAnswerPort;

    @Mock
    private GenerateTreasurerGuidePort generateTreasurerGuidePort;

    @Mock
    private CurrentActorPort currentActorPort;

    @Mock
    private SaveTreasurerAnswerPort saveTreasurerAnswerPort;

    /**
     * 기본은 안내 LLM이 데이터 질문이라고만 하고 의도는 키워드에 맡기는 상태다. 이때는 문장 다듬기 LLM을 부른다.
     * 안내 LLM이 응답하지 않는 경우는 {@link #guideUnavailable()}로 만든다.
     */
    @BeforeEach
    void guideDefersToKeywords() {
        lenient().when(generateTreasurerGuidePort.generate(any(), any())).thenReturn(Optional.of(
                new TreasurerGuideReply(TreasurerGuideReply.Route.DATA, null, List.of(), List.of())));
    }

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
    void answer_knownIntent_usesLlmPhrasing() {
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

        assertThat(result.answer()).isEqualTo("이번 달 보낼 정산이 1건 남아 있어요. 12,000원입니다."
                + " 가장 먼저 보낼 정산은 '회식 정산'이에요. 387시간 이상 지난, 오랫동안 처리되지 않은 정산이에요."
                + " 빨리 정산해 주세요.");
    }

    @Test
    void answer_llmInventsAmounts_usesFallback() {
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
    void expenseSummary_receiveSettlements_excluded() {
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
    void titleAndPeriodFilter_groupNamePresent_preserved() {
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
    void titleFilter_noMatches_returnsAllSettlements() {
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
    void titleHint_genericSuffix_strippedBeforeMatching() {
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
    void titleHint_genericMatch_doesNotNarrow() {
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
    void titleHint_containsName_narrowsResults() {
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
    void titleHint_genericWordAfterSuffixStripping_doesNotNarrow() {
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
    void statusAnswer_titleDropped_remainsGrounded() {
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
    void dueAnswer_titleDropped_remainsGrounded() {
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
    void answer_noSettlements_remainsTruthful() {
        when(treasurerSettlementQuery.loadMine()).thenReturn(List.of());
        AskTreasurerService service = service();

        var result = service.ask(new AskTreasurerCommand("이번 달 여행 모임에서 보낼 정산 얼마야?"));

        assertThat(result.items()).isEmpty();
        assertThat(result.answer()).contains("보낼 정산은 없어요");
        assertThat(result.scope()).contains("여행모임").contains("찾지 못해");
        verifyNoInteractions(generateTreasurerAnswerPort);
    }

    @Test
    void titleOutsidePeriod_periodFilter_keepsTitleFilter() {
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
    void receiveAnswers_draft_excluded() {
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
    void askSettlement_periodOmitted_infersCurrentMonth() {
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
    void sendSettlements_lastMonth_returned() {
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
    void settlementStatus_openAndCompletedCounts_returned() {
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
    void expenseSummary_completedSendIncludedAndDraftExcluded() {
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
    void unknownIntent_returnsExamples() {
        guideUnavailable();
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
    void serviceQuestion_asksForClarification(String question) {
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
    void colloquialDirection_matchesSettlementDirection(String question, SettlementDirection expected) {
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
    void colloquialSettlementQuestion_answeredWithoutClarification(String question, InquiryIntent expected) {
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
    void outOfScopeQuestion_returnsScopeGuide(String question) {
        guideUnavailable();
        AskTreasurerService service = service();

        var result = service.ask(new AskTreasurerCommand(question));

        assertThat(result.view()).isEqualTo(TreasurerAnswerView.TEXT);
        assertThat(result.answer()).contains("이렇게 물어볼 수 있어요");
        assertThat(result.answer()).doesNotContain("정산과 관련된 질문으로 이해했어요");
        verifyNoInteractions(generateTreasurerAnswerPort);
        verifyNoInteractions(treasurerSettlementQuery);
    }

    @Test
    void answerItems_includeAllocationTypeAndStatus() {
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
    void quickSendAction_returnsListCard() {
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
    void quickReceiveAction_returnsListCard() {
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
    void quickStatusAction_returnsSummaryCard() {
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
    void ocrGuide_answeredWithoutLlm() {
        AskTreasurerService service = service();

        var result = service.ask(new AskTreasurerCommand("OCR 정산은 어떻게 해?"));

        assertThat(result.intent()).isEqualTo(InquiryIntent.GUIDE);
        assertThat(result.answer()).contains("[영수증 인식]");
        verifyNoInteractions(generateTreasurerAnswerPort);
        verifyNoInteractions(treasurerSettlementQuery);
    }

    @Test
    void dueSettlements_thisWeek_returnsByDeadline() {
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
    void answerGenerator_llmOrServer_persisted() {
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
    void answerGenerator_factCheckedLlm_persisted() {
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
    void answer_unknownIntent_persisted() {
        AskTreasurerService service = service();

        var result = service.ask(new AskTreasurerCommand("오늘 날씨 어때"));

        verify(saveTreasurerAnswerPort).save(any(SavedTreasurerAnswer.class));
        assertThat(result.answerId()).isNotNull();
    }

    @Test
    @DisplayName("저장이 실패해도 답변은 나가고 answerId만 비어 있다")
    void answerPersistenceFailure_responseReturnedWithoutId() {
        doThrow(new RuntimeException("저장 실패"))
                .when(saveTreasurerAnswerPort).save(any(SavedTreasurerAnswer.class));
        AskTreasurerService service = service();

        var result = service.ask(new AskTreasurerCommand("오늘 날씨 어때"));

        assertThat(result.answerId()).isNull();
        assertThat(result.answer()).isNotBlank();
    }

    @Test
    @DisplayName("정산 관련이지만 키워드로 못 알아들은 질문은 서비스 안내 문서로 답한다")
    void relatedUnknown_guideAccepted_returnsGuideAnswer() {
        when(generateTreasurerGuidePort.generate(eq("독촉 알림 또 보내고 싶어"), eq(KNOWLEDGE)))
                .thenReturn(Optional.of(new TreasurerGuideReply(
                        TreasurerGuideReply.Route.GUIDE,
                        "독촉 알림은 한 번 보내면 30분 동안 다시 보낼 수 없어요.",
                        List.of("REMIND"),
                        List.of("송금은 어떻게 해?", "정기 정산도 돼?")
                )));
        AskTreasurerService service = service();

        var result = service.ask(new AskTreasurerCommand("독촉 알림 또 보내고 싶어"));

        assertThat(result.intent()).isEqualTo(InquiryIntent.GUIDE);
        assertThat(result.view()).isEqualTo(TreasurerAnswerView.TEXT);
        assertThat(result.answer()).isEqualTo("독촉 알림은 한 번 보내면 30분 동안 다시 보낼 수 없어요.");
        assertThat(result.prompts())
                .extracting(TreasurerAnswerResult.Prompt::question)
                .containsExactly("송금은 어떻게 해?", "정기 정산도 돼?");
        assertThat(result.prompts())
                .extracting(TreasurerAnswerResult.Prompt::type)
                .containsOnly(InquiryIntent.GUIDE);
        verifyNoInteractions(treasurerSettlementQuery);
        verifyNoInteractions(generateTreasurerAnswerPort);
    }

    @Test
    @DisplayName("안내 답변은 LLM이 만든 문장으로 저장해 신고 때 구분한다")
    void guideAnswer_savedAsLlm() {
        when(generateTreasurerGuidePort.generate(any(), any()))
                .thenReturn(Optional.of(new TreasurerGuideReply(
                        TreasurerGuideReply.Route.GUIDE,
                        "정산 상세에서 [독촉 알림 보내기]를 누르면 돼요.",
                        List.of("REMIND"),
                        List.of()
                )));
        AskTreasurerService service = service();

        service.ask(new AskTreasurerCommand("독촉 알림 또 보내고 싶어"));

        ArgumentCaptor<SavedTreasurerAnswer> saved = ArgumentCaptor.forClass(SavedTreasurerAnswer.class);
        verify(saveTreasurerAnswerPort).save(saved.capture());
        assertThat(saved.getValue().generatedBy()).isEqualTo(TreasurerAnswerGeneratedBy.LLM);
        assertThat(saved.getValue().intent()).isEqualTo(InquiryIntent.GUIDE);
    }

    @Test
    @DisplayName("안내 LLM이 없으면 얼마와 관계없는 질문은 키워드 분류대로 질문 예시를 보여준다")
    void unrelatedQuestion_guideUnavailable_returnsExamples() {
        guideUnavailable();
        AskTreasurerService service = service();

        var result = service.ask(new AskTreasurerCommand("푸딩은 어떻게 만드나요?"));

        assertThat(result.answer()).contains("이렇게 물어볼 수 있어요");
        verify(generateTreasurerGuidePort).generate(eq("푸딩은 어떻게 만드나요?"), eq(KNOWLEDGE));
    }

    @Test
    @DisplayName("안내 LLM이 없으면 다른 낱말의 첫머리로 쓰인 돈은 관련 단어로 보지 않는다")
    void wordPrefixedMoneyTerm_guideUnavailable_returnsOutOfScope() {
        guideUnavailable();
        AskTreasurerService service = service();

        var result = service.ask(new AskTreasurerCommand("돈까스 만드는 방법 알려줘"));

        assertThat(result.answer()).isEqualTo(TreasurerCardCopy.UNKNOWN_ANSWER);
    }

    @Test
    @DisplayName("사용법 말투의 데이터 질문을 안내가 무관하다고 하면 정산을 보여주지 않고 범위를 안내한다")
    void howToWithDataKeyword_guideSaysUnrelated_returnsExamples() {
        when(generateTreasurerGuidePort.generate(eq("비트코인 송금은 어떻게 해?"), any()))
                .thenReturn(Optional.of(new TreasurerGuideReply(
                        TreasurerGuideReply.Route.UNRELATED, null, List.of(), List.of())));
        AskTreasurerService service = service();

        var result = service.ask(new AskTreasurerCommand("비트코인 송금은 어떻게 해?"));

        assertThat(result.intent()).isEqualTo(InquiryIntent.UNKNOWN);
        assertThat(result.answer()).isEqualTo(TreasurerCardCopy.UNKNOWN_ANSWER);
        assertThat(result.prompts()).isNotEmpty();
        verifyNoInteractions(treasurerSettlementQuery);
    }

    @Test
    @DisplayName("영수증 안내 질문을 안내가 무관하다고 하면 고정 영수증 안내 대신 범위를 안내한다")
    void guideIntent_guideSaysUnrelated_returnsExamples() {
        when(generateTreasurerGuidePort.generate(any(), any()))
                .thenReturn(Optional.of(new TreasurerGuideReply(
                        TreasurerGuideReply.Route.UNRELATED, null, List.of(), List.of())));
        AskTreasurerService service = service();

        var result = service.ask(new AskTreasurerCommand("종이 영수증 오래 보관하는 방법 알려줘"));

        assertThat(result.intent()).isEqualTo(InquiryIntent.UNKNOWN);
        assertThat(result.answer()).isEqualTo(TreasurerCardCopy.UNKNOWN_ANSWER);
    }

    @Test
    @DisplayName("데이터 키워드가 걸려도 사용법을 묻는 말투면 안내로 답하고 정산을 조회하지 않는다")
    void howToWithDataKeyword_guideAccepted_skipsSettlementLookup() {
        when(generateTreasurerGuidePort.generate(eq("송금은 어떻게 해?"), any()))
                .thenReturn(Optional.of(new TreasurerGuideReply(
                        TreasurerGuideReply.Route.GUIDE,
                        "정산 참여 화면에서 [토스로 N원 보내기]를 누르면 토스가 열려요.",
                        List.of("PAY"),
                        List.of()
                )));
        AskTreasurerService service = service();

        var result = service.ask(new AskTreasurerCommand("송금은 어떻게 해?"));

        assertThat(result.intent()).isEqualTo(InquiryIntent.GUIDE);
        assertThat(result.answer()).contains("토스");
        verifyNoInteractions(treasurerSettlementQuery);
    }

    @Test
    @DisplayName("안내가 데이터 질문이라고 돌려보내면 지금처럼 정산을 조회한다")
    void howToWithDataKeyword_guideSaysData_answersFromSettlements() {
        when(generateTreasurerGuidePort.generate(any(), any()))
                .thenReturn(Optional.of(new TreasurerGuideReply(
                        TreasurerGuideReply.Route.DATA, null, List.of(), List.of())));
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
        when(generateTreasurerAnswerPort.generate(any())).thenReturn(Optional.empty());
        AskTreasurerService service = service();

        var result = service.ask(new AskTreasurerCommand("이번 달 보낼 돈은 어떻게 보내?"));

        assertThat(result.intent()).isEqualTo(InquiryIntent.SEND_SETTLEMENTS);
        assertThat(result.answer()).contains("1건").contains("12,000원");
    }

    @Test
    @DisplayName("안내 LLM이 없으면 내 금액을 묻는 흔한 말투는 키워드 분류대로 정산을 조회한다")
    void dataPhrasing_guideUnavailable_answersFromSettlements() {
        guideUnavailable();
        when(treasurerSettlementQuery.loadMine()).thenReturn(List.of());
        AskTreasurerService service = service();

        var result = service.ask(new AskTreasurerCommand("이번 달 보낼 돈 어떻게 돼?"));

        assertThat(result.intent()).isEqualTo(InquiryIntent.SEND_SETTLEMENTS);
        verify(generateTreasurerGuidePort).generate(eq("이번 달 보낼 돈 어떻게 돼?"), eq(KNOWLEDGE));
    }

    @Test
    @DisplayName("안내 LLM을 쓸 수 없으면 사용법 말투여도 지금처럼 정산을 조회한다")
    void howToWithDataKeyword_guideUnavailable_answersFromSettlements() {
        guideUnavailable();
        when(treasurerSettlementQuery.loadMine()).thenReturn(List.of());
        AskTreasurerService service = service();

        var result = service.ask(new AskTreasurerCommand("송금은 어떻게 해?"));

        assertThat(result.intent()).isEqualTo(InquiryIntent.SEND_SETTLEMENTS);
        verify(generateTreasurerGuidePort).generate(eq("송금은 어떻게 해?"), eq(KNOWLEDGE));
    }

    @Test
    @DisplayName("문서에 없는 근거 ID를 댄 안내 답변은 버리고 고정 안내로 되돌린다")
    void guideWithUnknownSource_fallsBackToClarify() {
        when(generateTreasurerGuidePort.generate(any(), any()))
                .thenReturn(Optional.of(new TreasurerGuideReply(
                        TreasurerGuideReply.Route.GUIDE,
                        "독촉 알림은 하루에 세 번까지 보낼 수 있어요.",
                        List.of("MADE-UP"),
                        List.of()
                )));
        AskTreasurerService service = service();

        var result = service.ask(new AskTreasurerCommand("독촉 알림 또 보내고 싶어"));

        assertThat(result.intent()).isEqualTo(InquiryIntent.UNKNOWN);
        assertThat(result.answer()).isEqualTo(TreasurerCardCopy.CLARIFY_ANSWER);
    }

    @Test
    @DisplayName("안내가 관계없는 질문이라고 하면 질문 예시를 보여준다")
    void guideSaysUnrelated_returnsExamples() {
        when(generateTreasurerGuidePort.generate(any(), any()))
                .thenReturn(Optional.of(new TreasurerGuideReply(
                        TreasurerGuideReply.Route.UNRELATED, null, List.of(), List.of())));
        AskTreasurerService service = service();

        var result = service.ask(new AskTreasurerCommand("얼마 앱으로 주식도 살 수 있어?"));

        assertThat(result.intent()).isEqualTo(InquiryIntent.UNKNOWN);
        assertThat(result.answer()).isEqualTo(TreasurerCardCopy.UNKNOWN_ANSWER);
        assertThat(result.prompts()).isNotEmpty();
    }

    @Test
    @DisplayName("영수증 안내 질문은 안내 문서 답이 있으면 그 답을 쓴다")
    void guideIntent_guideAccepted_replacesFixedOcrAnswer() {
        when(generateTreasurerGuidePort.generate(any(), any()))
                .thenReturn(Optional.of(new TreasurerGuideReply(
                        TreasurerGuideReply.Route.GUIDE,
                        "정산 만들기에서 [영수증 인식]을 누르면 돼요.",
                        List.of("CREATE-AMOUNT"),
                        List.of()
                )));
        AskTreasurerService service = service();

        var result = service.ask(new AskTreasurerCommand("영수증으로 정산하려면 어떻게 해?"));

        assertThat(result.intent()).isEqualTo(InquiryIntent.GUIDE);
        assertThat(result.answer()).isEqualTo("정산 만들기에서 [영수증 인식]을 누르면 돼요.");
    }

    @Test
    @DisplayName("뭐부터 처리할지 물으면 기간 없이 끝나지 않은 정산 전체를 우선순위대로 보여주고 급한 보낼 정산을 알린다")
    void priority_noPeriod_allOutstandingOrderedWithNotice() {
        PrioritySettlements fixture = prioritySettlements();
        when(treasurerSettlementQuery.loadMine()).thenReturn(fixture.all());
        AskTreasurerService service = service();

        var result = service.ask(new AskTreasurerCommand("뭐부터 처리해?"));

        assertThat(result.intent()).isEqualTo(InquiryIntent.PRIORITY_SETTLEMENTS);
        assertThat(result.period()).isNull();
        assertThat(result.view()).isEqualTo(TreasurerAnswerView.LIST);
        assertThat(result.scope()).isEqualTo("끝나지 않은 정산 전체를 기준으로 조회했어요.");
        assertThat(result.answer()).isEqualTo("끝나지 않은 정산 전체를 기준으로 조회했어요. 먼저 처리할 정산은 5건이에요."
                + " 가장 먼저 확인할 정산은 '지난달 회식'이에요."
                + " 648시간 이상 지난, 오랫동안 처리되지 않은 정산이에요. 빨리 정산해 주세요.");
        assertThat(result.items()).extracting(TreasurerAnswerResult.Item::settlementId).containsExactly(
                fixture.lastMonthSend().settlementId(),
                fixture.overdueSend().settlementId(),
                fixture.freshSend().settlementId(),
                fixture.overdueReceive().settlementId(),
                fixture.freshReceive().settlementId());
        assertThat(result.items()).extracting(TreasurerAnswerResult.Item::marker).containsExactly(
                TreasurerCardMarker.ATTENTION,
                TreasurerCardMarker.ATTENTION,
                TreasurerCardMarker.NONE,
                TreasurerCardMarker.MORE,
                TreasurerCardMarker.MORE);
        verifyNoInteractions(generateTreasurerAnswerPort);
        ArgumentCaptor<SavedTreasurerAnswer> captor = ArgumentCaptor.forClass(SavedTreasurerAnswer.class);
        verify(saveTreasurerAnswerPort).save(captor.capture());
        assertThat(captor.getValue().generatedBy()).isEqualTo(TreasurerAnswerGeneratedBy.SERVER);
    }

    @Test
    @DisplayName("기간을 말하면 그 기간 안에서만 우선순위를 정한다")
    void priority_explicitPeriod_filtered() {
        PrioritySettlements fixture = prioritySettlements();
        when(treasurerSettlementQuery.loadMine()).thenReturn(fixture.all());
        AskTreasurerService service = service();

        var result = service.ask(new AskTreasurerCommand("이번 달 정산 뭐부터 하면 돼?"));

        assertThat(result.intent()).isEqualTo(InquiryIntent.PRIORITY_SETTLEMENTS);
        assertThat(result.period()).isEqualTo(InquiryPeriod.THIS_MONTH);
        assertThat(result.answer()).isEqualTo("이번 달 기준으로 조회했어요. 먼저 처리할 정산은 4건이에요."
                + " 가장 먼저 확인할 정산은 '제주 여행'이에요. 26시간 이상 지난 정산이에요. 빨리 정산해 주세요.");
        assertThat(result.items()).extracting(TreasurerAnswerResult.Item::settlementId)
                .doesNotContain(fixture.lastMonthSend().settlementId())
                .first()
                .isEqualTo(fixture.overdueSend().settlementId());
    }

    @Test
    @DisplayName("맨 위가 받을 정산이면 경과 시간 안내를 붙이지 않는다")
    void priority_topReceive_noUrgentNotice() {
        PrioritySettlements fixture = prioritySettlements();
        when(treasurerSettlementQuery.loadMine()).thenReturn(List.of(fixture.freshReceive(), fixture.overdueReceive()));
        AskTreasurerService service = service();

        var result = service.ask(new AskTreasurerCommand("보낼 정산과 받을 정산 정리해줘"));

        assertThat(result.intent()).isEqualTo(InquiryIntent.PRIORITY_SETTLEMENTS);
        assertThat(result.answer()).isEqualTo("끝나지 않은 정산 전체를 기준으로 조회했어요. 먼저 처리할 정산은 2건이에요."
                + " 가장 먼저 확인할 정산은 '동아리 회비'예요.");
        assertThat(result.items()).extracting(TreasurerAnswerResult.Item::settlementId).containsExactly(
                fixture.overdueReceive().settlementId(), fixture.freshReceive().settlementId());
    }

    @Test
    void priority_nothingOutstanding_saysNone() {
        PrioritySettlements fixture = prioritySettlements();
        when(treasurerSettlementQuery.loadMine()).thenReturn(List.of(fixture.completed(), fixture.draft()));
        AskTreasurerService service = service();

        var result = service.ask(new AskTreasurerCommand("뭐부터 처리해?"));

        assertThat(result.intent()).isEqualTo(InquiryIntent.PRIORITY_SETTLEMENTS);
        assertThat(result.answer()).isEqualTo("끝나지 않은 정산 전체를 기준으로 조회했어요. 먼저 처리할 정산은 없어요.");
        assertThat(result.items()).isEmpty();
        assertThat(result.footer()).isNull();
    }

    @Test
    @DisplayName("보낼 정산 목록도 우선순위대로 보여주고 맨 위가 기한을 넘겼으면 경과 시간을 알린다")
    void sendList_orderedWithUrgentNotice() {
        PrioritySettlements fixture = prioritySettlements();
        when(treasurerSettlementQuery.loadMine()).thenReturn(fixture.all());
        when(generateTreasurerAnswerPort.generate(any())).thenReturn(Optional.empty());
        AskTreasurerService service = service();

        var result = service.ask(new AskTreasurerCommand("보낼 정산 알려줘"));

        assertThat(result.intent()).isEqualTo(InquiryIntent.SEND_SETTLEMENTS);
        assertThat(result.items()).extracting(TreasurerAnswerResult.Item::settlementId).containsExactly(
                fixture.overdueSend().settlementId(), fixture.freshSend().settlementId());
        assertThat(result.answer()).endsWith(
                " 가장 먼저 보낼 정산은 '제주 여행'이에요. 26시간 이상 지난 정산이에요. 빨리 정산해 주세요.");
    }

    @Test
    @DisplayName("LLM 문장이 채택돼도 급한 보낼 정산 안내는 한 번만 덧붙인다")
    void sendList_llmAccepted_noticeAppendedOnce() {
        PrioritySettlements fixture = prioritySettlements();
        when(treasurerSettlementQuery.loadMine()).thenReturn(fixture.all());
        when(generateTreasurerAnswerPort.generate(any()))
                .thenAnswer(invocation -> Optional.of(invocation.getArgument(0, TreasurerAnswerDraft.class)
                        .fallbackAnswer()));
        AskTreasurerService service = service();

        var result = service.ask(new AskTreasurerCommand("보낼 정산 알려줘"));

        assertThat(result.answer()).endsWith(" 26시간 이상 지난 정산이에요. 빨리 정산해 주세요.");
        assertThat(result.answer().split("가장 먼저 보낼 정산은", -1)).hasSize(2);
        ArgumentCaptor<SavedTreasurerAnswer> captor = ArgumentCaptor.forClass(SavedTreasurerAnswer.class);
        verify(saveTreasurerAnswerPort).save(captor.capture());
        assertThat(captor.getValue().answer()).isEqualTo(result.answer());
    }

    @Test
    void priority_titleHint_narrowsCandidates() {
        PersonalSettlement jeju = prioritySettlement("제주 여행 정산", SettlementDirection.RECEIVE,
                SettlementStatus.REQUESTED, LocalDateTime.of(2026, 8, 26, 0, 0), 4_000L);
        PrioritySettlements fixture = prioritySettlements();
        when(treasurerSettlementQuery.loadMine()).thenReturn(List.of(jeju, fixture.overdueSend()));
        AskTreasurerService service = service();

        var result = service.ask(new AskTreasurerCommand("제주 여행 정산 중에 뭐부터 해?"));

        assertThat(result.intent()).isEqualTo(InquiryIntent.PRIORITY_SETTLEMENTS);
        assertThat(result.scope()).startsWith("끝나지 않은 정산 전체를 기준으로 조회했어요.").contains("좁혀서");
        assertThat(result.items()).extracting(TreasurerAnswerResult.Item::settlementId)
                .containsExactly(jeju.settlementId());
    }

    @Test
    void sendList_topNotOverdue_noNotice() {
        PrioritySettlements fixture = prioritySettlements();
        when(treasurerSettlementQuery.loadMine()).thenReturn(List.of(fixture.freshSend()));
        when(generateTreasurerAnswerPort.generate(any())).thenReturn(Optional.empty());
        AskTreasurerService service = service();

        var result = service.ask(new AskTreasurerCommand("보낼 정산 알려줘"));

        assertThat(result.answer()).doesNotContain("시간 이상");
        assertThat(result.items()).extracting(TreasurerAnswerResult.Item::marker)
                .containsExactly(TreasurerCardMarker.NONE);
    }

    @Test
    void receiveList_overdueFirst() {
        PrioritySettlements fixture = prioritySettlements();
        when(treasurerSettlementQuery.loadMine()).thenReturn(fixture.all());
        when(generateTreasurerAnswerPort.generate(any())).thenReturn(Optional.empty());
        AskTreasurerService service = service();

        var result = service.ask(new AskTreasurerCommand("받을 정산 알려줘"));

        assertThat(result.intent()).isEqualTo(InquiryIntent.RECEIVE_SETTLEMENTS);
        assertThat(result.items()).extracting(TreasurerAnswerResult.Item::settlementId).containsExactly(
                fixture.overdueReceive().settlementId(), fixture.freshReceive().settlementId());
        assertThat(result.answer()).doesNotContain("시간 이상");
    }

    @Test
    @DisplayName("키워드로 못 나누는 말투도 안내 LLM이 우선순위 질문이라고 하면 처리할 정산을 보여준다")
    void llmPriority_unmatchedPhrasing_answersPriority() {
        PrioritySettlements fixture = prioritySettlements();
        when(treasurerSettlementQuery.loadMine()).thenReturn(fixture.all());
        String question = "내가 지금 해야하는 정산";
        llmSaysData(question, InquiryIntent.PRIORITY_SETTLEMENTS, null, null);
        AskTreasurerService service = service();

        var result = service.ask(new AskTreasurerCommand(question));

        assertThat(new InquiryParser().parse(question).intent()).isEqualTo(InquiryIntent.UNKNOWN);
        assertThat(result.intent()).isEqualTo(InquiryIntent.PRIORITY_SETTLEMENTS);
        assertThat(result.items()).isNotEmpty();
    }

    @Test
    @DisplayName("안내 LLM이 보낼 정산이라고 하면 받을 정산이 함께 있어도 보낼 정산만 보여준다")
    void llmSend_onlySendSettlements() {
        PrioritySettlements fixture = prioritySettlements();
        when(treasurerSettlementQuery.loadMine()).thenReturn(fixture.all());
        String question = "내가 지금 해야하는 정산";
        llmSaysData(question, InquiryIntent.SEND_SETTLEMENTS, null, null);
        AskTreasurerService service = service();

        var result = service.ask(new AskTreasurerCommand(question));

        assertThat(result.intent()).isEqualTo(InquiryIntent.SEND_SETTLEMENTS);
        assertThat(result.items()).extracting(TreasurerAnswerResult.Item::settlementId)
                .containsExactlyInAnyOrder(fixture.overdueSend().settlementId(), fixture.freshSend().settlementId());
    }

    @Test
    @DisplayName("키워드와 안내 LLM의 의도가 다르면 안내 LLM을 따른다")
    void llmIntent_overridesKeyword() {
        PrioritySettlements fixture = prioritySettlements();
        when(treasurerSettlementQuery.loadMine()).thenReturn(fixture.all());
        String question = "받을 사람한테 보낼 정산 알려줘";
        llmSaysData(question, InquiryIntent.SEND_SETTLEMENTS, null, null);
        AskTreasurerService service = service();

        var result = service.ask(new AskTreasurerCommand(question));

        assertThat(result.intent()).isEqualTo(InquiryIntent.SEND_SETTLEMENTS);
        assertThat(result.items()).extracting(TreasurerAnswerResult.Item::settlementId)
                .containsExactlyInAnyOrder(fixture.overdueSend().settlementId(), fixture.freshSend().settlementId());
    }

    @Test
    @DisplayName("안내 LLM이 데이터 질문이지만 의도를 정하지 못했다고 하면 정산을 조회하지 않고 되묻는다")
    void llmUnclear_clarifies() {
        String question = "보낼 정산이랑 받을 정산 중에 뭐가 많아?";
        llmSaysData(question, InquiryIntent.UNKNOWN, null, null);
        AskTreasurerService service = service();

        var result = service.ask(new AskTreasurerCommand(question));

        assertThat(result.intent()).isEqualTo(InquiryIntent.UNKNOWN);
        assertThat(result.answer()).isEqualTo(TreasurerCardCopy.CLARIFY_ANSWER);
        assertThat(result.prompts()).isNotEmpty();
        verifyNoInteractions(treasurerSettlementQuery);
    }

    @Test
    @DisplayName("안내 LLM이 데이터 질문이라면서 의도를 비우면 키워드 분류를 쓰고, 키워드도 모르면 되묻는다")
    void llmDataWithoutIntent_usesKeywordOrClarifies() {
        PrioritySettlements fixture = prioritySettlements();
        when(treasurerSettlementQuery.loadMine()).thenReturn(fixture.all());
        llmSaysData("받을 정산 알려줘", null, null, null);
        llmSaysData("정산 좀 봐줘", null, null, null);
        AskTreasurerService service = service();

        var keyword = service.ask(new AskTreasurerCommand("받을 정산 알려줘"));
        var unmatched = service.ask(new AskTreasurerCommand("정산 좀 봐줘"));

        assertThat(keyword.intent()).isEqualTo(InquiryIntent.RECEIVE_SETTLEMENTS);
        assertThat(keyword.items()).extracting(TreasurerAnswerResult.Item::settlementId).containsExactly(
                fixture.overdueReceive().settlementId(), fixture.freshReceive().settlementId());
        assertThat(unmatched.answer()).isEqualTo(TreasurerCardCopy.CLARIFY_ANSWER);
    }

    @Test
    @DisplayName("질문에 기간이 있으면 안내 LLM이 정한 기간과 질문에 적힌 정산 이름으로 좁힌다")
    void llmPeriodAndTitle_narrowSettlements() {
        PrioritySettlements fixture = prioritySettlements();
        when(treasurerSettlementQuery.loadMine()).thenReturn(fixture.all());
        String periodQuestion = "지난달 말고 이번 달 보낼 돈 알려줘";
        llmSaysData(periodQuestion, InquiryIntent.SEND_SETTLEMENTS, InquiryPeriod.THIS_MONTH, null);
        llmSaysData("제주 여행 보낼 돈 알려줘", InquiryIntent.SEND_SETTLEMENTS, null, "제주 여행");
        AskTreasurerService service = service();

        var thisMonth = service.ask(new AskTreasurerCommand(periodQuestion));
        var titled = service.ask(new AskTreasurerCommand("제주 여행 보낼 돈 알려줘"));

        assertThat(new InquiryParser().parse(periodQuestion).period()).isEqualTo(InquiryPeriod.LAST_MONTH);
        assertThat(thisMonth.period()).isEqualTo(InquiryPeriod.THIS_MONTH);
        assertThat(thisMonth.items()).extracting(TreasurerAnswerResult.Item::settlementId)
                .containsExactlyInAnyOrder(fixture.overdueSend().settlementId(), fixture.freshSend().settlementId());
        assertThat(titled.items()).extracting(TreasurerAnswerResult.Item::settlementId)
                .containsExactly(fixture.overdueSend().settlementId());
    }

    @Test
    @DisplayName("질문에 기간 표현이 없으면 안내 LLM이 기간을 채워도 쓰지 않는다")
    void llmPeriod_withoutPeriodInQuestion_ignored() {
        PrioritySettlements fixture = prioritySettlements();
        when(treasurerSettlementQuery.loadMine()).thenReturn(fixture.all());
        String question = "뭐부터 처리해?";
        llmSaysData(question, InquiryIntent.PRIORITY_SETTLEMENTS, InquiryPeriod.THIS_MONTH, null);
        AskTreasurerService service = service();

        var result = service.ask(new AskTreasurerCommand(question));

        assertThat(result.period()).isNull();
        assertThat(result.items()).extracting(TreasurerAnswerResult.Item::settlementId)
                .contains(fixture.lastMonthSend().settlementId());
    }

    @Test
    @DisplayName("안내 LLM이 기간을 비우면 질문에서 키워드가 뽑은 기간을 쓴다")
    void llmPeriodEmpty_usesKeywordPeriod() {
        PrioritySettlements fixture = prioritySettlements();
        when(treasurerSettlementQuery.loadMine()).thenReturn(fixture.all());
        String question = "지난달에 내가 내야 하는 거";
        llmSaysData(question, InquiryIntent.SEND_SETTLEMENTS, null, null);
        AskTreasurerService service = service();

        var result = service.ask(new AskTreasurerCommand(question));

        assertThat(result.period()).isEqualTo(InquiryPeriod.LAST_MONTH);
        assertThat(result.items()).extracting(TreasurerAnswerResult.Item::settlementId)
                .containsExactly(fixture.lastMonthSend().settlementId());
    }

    @Test
    @DisplayName("안내 LLM이 준 이름이 정산과 맞지 않으면 키워드가 뽑은 이름으로 다시 좁힌다")
    void llmTitleUnmatched_fallsBackToKeywordTitle() {
        PrioritySettlements fixture = prioritySettlements();
        when(treasurerSettlementQuery.loadMine()).thenReturn(fixture.all());
        String question = "제주 여행 모임에서 보낼 돈 알려줘";
        llmSaysData(question, InquiryIntent.SEND_SETTLEMENTS, null, "제주 여행 모임에서");
        AskTreasurerService service = service();

        var result = service.ask(new AskTreasurerCommand(question));

        assertThat(new InquiryParser().parse(question).titleHint()).isNotNull();
        assertThat(result.items()).extracting(TreasurerAnswerResult.Item::settlementId)
                .containsExactly(fixture.overdueSend().settlementId());
        assertThat(result.scope()).doesNotContain("찾지 못해");
    }

    @Test
    @DisplayName("안내 LLM이 데이터 질문이라면서 의도를 비우고 키워드가 영수증 안내면 영수증 안내로 답한다")
    void llmDataWithoutIntent_keywordGuide_answersOcr() {
        String question = "영수증 인식은 어떻게 해?";
        llmSaysData(question, null, null, null);
        AskTreasurerService service = service();

        var result = service.ask(new AskTreasurerCommand(question));

        assertThat(new InquiryParser().parse(question).intent()).isEqualTo(InquiryIntent.GUIDE);
        assertThat(result.answer()).isEqualTo(TreasurerCardCopy.OCR_ANSWER);
    }

    @Test
    @DisplayName("질문에 없는 정산 이름을 안내 LLM이 지어내면 그 이름으로 좁히지 않는다")
    void llmTitleNotInQuestion_ignored() {
        PrioritySettlements fixture = prioritySettlements();
        when(treasurerSettlementQuery.loadMine()).thenReturn(fixture.all());
        String question = "보낼 돈 알려줘";
        llmSaysData(question, InquiryIntent.SEND_SETTLEMENTS, null, "제주 여행");
        AskTreasurerService service = service();

        var result = service.ask(new AskTreasurerCommand(question));

        assertThat(result.items()).extracting(TreasurerAnswerResult.Item::settlementId)
                .containsExactlyInAnyOrder(fixture.overdueSend().settlementId(), fixture.freshSend().settlementId());
    }

    @Test
    @DisplayName("안내 답변이 검사에서 버려지면 안내 LLM을 다시 부르지 않고 키워드 분류로 답한다")
    void rejectedGuide_fallsBackToKeywordWithoutSecondCall() {
        when(treasurerSettlementQuery.loadMine()).thenReturn(List.of());
        String question = "보낼 정산 알려줘";
        when(generateTreasurerGuidePort.generate(eq(question), any()))
                .thenReturn(Optional.of(new TreasurerGuideReply(
                        TreasurerGuideReply.Route.GUIDE, "없는 근거로 답해요.", List.of("NOPE"), List.of())));
        AskTreasurerService service = service();

        var result = service.ask(new AskTreasurerCommand(question));

        assertThat(result.intent()).isEqualTo(InquiryIntent.SEND_SETTLEMENTS);
        verify(generateTreasurerGuidePort, times(1)).generate(eq(question), any());
    }

    @Test
    @DisplayName("안내 LLM이 응답하지 않으면 문장 다듬기 LLM도 부르지 않고 서버 문구로 답한다")
    void guideUnavailable_skipsPhrasingLlm() {
        guideUnavailable();
        PrioritySettlements fixture = prioritySettlements();
        when(treasurerSettlementQuery.loadMine()).thenReturn(fixture.all());
        AskTreasurerService service = service();

        var list = service.ask(new AskTreasurerCommand("받을 정산 알려줘"));
        var status = service.ask(new AskTreasurerCommand("내 정산 중에 뭐가 남았어?"));

        assertThat(list.intent()).isEqualTo(InquiryIntent.RECEIVE_SETTLEMENTS);
        assertThat(list.items()).isNotEmpty();
        assertThat(status.intent()).isEqualTo(InquiryIntent.SETTLEMENT_STATUS);
        verifyNoInteractions(generateTreasurerAnswerPort);
        ArgumentCaptor<SavedTreasurerAnswer> captor = ArgumentCaptor.forClass(SavedTreasurerAnswer.class);
        verify(saveTreasurerAnswerPort, times(2)).save(captor.capture());
        assertThat(captor.getAllValues()).extracting(SavedTreasurerAnswer::generatedBy)
                .containsOnly(TreasurerAnswerGeneratedBy.SERVER);
    }

    private void guideUnavailable() {
        when(generateTreasurerGuidePort.generate(any(), any())).thenReturn(Optional.empty());
    }

    private void llmSaysData(String question, InquiryIntent intent, InquiryPeriod period, String titleHint) {
        when(generateTreasurerGuidePort.generate(eq(question), any()))
                .thenReturn(Optional.of(new TreasurerGuideReply(
                        TreasurerGuideReply.Route.DATA, null, List.of(), List.of(), intent, period, titleHint)));
    }

    private record PrioritySettlements(
            PersonalSettlement lastMonthSend,
            PersonalSettlement overdueSend,
            PersonalSettlement freshSend,
            PersonalSettlement overdueReceive,
            PersonalSettlement freshReceive,
            PersonalSettlement completed,
            PersonalSettlement draft
    ) {
        List<PersonalSettlement> all() {
            return List.of(freshReceive, completed, overdueReceive, freshSend, draft, overdueSend, lastMonthSend);
        }
    }

    private static PrioritySettlements prioritySettlements() {
        return new PrioritySettlements(
                prioritySettlement("지난달 회식", SettlementDirection.SEND, SettlementStatus.REQUESTED,
                        LocalDateTime.of(2026, 7, 30, 3, 0), 10_000L),
                prioritySettlement("제주 여행", SettlementDirection.SEND, SettlementStatus.AWAITING_DEPOSIT,
                        LocalDateTime.of(2026, 8, 25, 1, 0), 30_000L),
                prioritySettlement("점심", SettlementDirection.SEND, SettlementStatus.REQUESTED,
                        LocalDateTime.of(2026, 8, 26, 1, 0), 8_000L),
                prioritySettlement("동아리 회비", SettlementDirection.RECEIVE, SettlementStatus.AWAITING_DEPOSIT,
                        LocalDateTime.of(2026, 8, 20, 3, 0), 50_000L),
                prioritySettlement("커피", SettlementDirection.RECEIVE, SettlementStatus.REQUESTED,
                        LocalDateTime.of(2026, 8, 26, 0, 0), 4_000L),
                prioritySettlement("끝난 정산", SettlementDirection.SEND, SettlementStatus.COMPLETED,
                        LocalDateTime.of(2026, 8, 1, 0, 0), 7_000L),
                prioritySettlement("임시 정산", SettlementDirection.RECEIVE, SettlementStatus.DRAFT,
                        LocalDateTime.of(2026, 8, 1, 0, 0), 6_000L)
        );
    }

    private static PersonalSettlement prioritySettlement(
            String title,
            SettlementDirection direction,
            SettlementStatus status,
            LocalDateTime requestedAt,
            long amount
    ) {
        return new PersonalSettlement(UUID.randomUUID(), title, direction, status, requestedAt, amount, "요청자");
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
                Clock.fixed(NOW, ZoneOffset.UTC),
                () -> KNOWLEDGE,
                generateTreasurerGuidePort,
                new TreasurerGuideCheck()
        );
    }
}
