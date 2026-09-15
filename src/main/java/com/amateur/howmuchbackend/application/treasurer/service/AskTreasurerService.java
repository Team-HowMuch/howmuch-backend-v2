package com.amateur.howmuchbackend.application.treasurer.service;

import com.amateur.howmuchbackend.application.port.in.treasurer.AskTreasurerUseCase;
import com.amateur.howmuchbackend.application.port.in.treasurer.command.AskTreasurerCommand;
import com.amateur.howmuchbackend.application.port.in.treasurer.result.TreasurerAnswerResult;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.application.port.out.treasurer.GenerateTreasurerAnswerPort;
import com.amateur.howmuchbackend.application.port.out.treasurer.SaveTreasurerAnswerPort;
import com.amateur.howmuchbackend.application.treasurer.model.InquiryScope;
import com.amateur.howmuchbackend.application.treasurer.model.TreasurerAnswerDraft;
import com.amateur.howmuchbackend.application.treasurer.policy.InquiryParser;
import com.amateur.howmuchbackend.application.treasurer.policy.InquiryScopePolicy;
import com.amateur.howmuchbackend.application.treasurer.policy.TitleHintVerification;
import com.amateur.howmuchbackend.application.treasurer.policy.TreasurerAnswerFactCheck;
import com.amateur.howmuchbackend.application.treasurer.policy.TreasurerCardCopy;
import com.amateur.howmuchbackend.domain.treasurer.InquiryIntent;
import com.amateur.howmuchbackend.domain.treasurer.InquiryPeriod;
import com.amateur.howmuchbackend.domain.treasurer.PersonalSettlement;
import com.amateur.howmuchbackend.domain.treasurer.SavedTreasurerAnswer;
import com.amateur.howmuchbackend.domain.treasurer.TreasurerAnswerGeneratedBy;
import com.amateur.howmuchbackend.domain.treasurer.TreasurerAnswerActionType;
import com.amateur.howmuchbackend.domain.treasurer.TreasurerAnswerView;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.WeekFields;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class AskTreasurerService implements AskTreasurerUseCase {
    static final int CARD_LIMIT = 20;
    private static final ZoneId USER_ZONE = ZoneId.of("Asia/Seoul");

    private final TreasurerSettlementQuery treasurerSettlementQuery;
    private final InquiryParser inquiryParser;
    private final InquiryScopePolicy inquiryScopePolicy;
    private final TitleHintVerification titleHintVerification;
    private final GenerateTreasurerAnswerPort generateTreasurerAnswerPort;
    private final TreasurerAnswerFactCheck treasurerAnswerFactCheck;
    private final TreasurerCardCopy treasurerCardCopy;
    private final CurrentActorPort currentActorPort;
    private final SaveTreasurerAnswerPort saveTreasurerAnswerPort;
    private final Clock clock;

    @Override
    @Transactional(readOnly = true)
    public TreasurerAnswerResult ask(AskTreasurerCommand command) {
        Answered answered = answerFor(command);
        return answered.result().withAnswerId(saveAnswer(command.question(), answered));
    }

    /**
     * 답변을 남기고 그 식별자를 돌려준다.
     *
     * <p>저장은 답변의 부수 효과다. 실패하면 {@code null}을 돌려주고 답변 자체는 그대로 나간다.
     * 앱은 식별자가 없으면 신고 버튼을 숨긴다.
     */
    private UUID saveAnswer(String question, Answered answered) {
        try {
            SavedTreasurerAnswer saved = SavedTreasurerAnswer.of(
                    currentActorPort.getCurrentActor().userId(),
                    question,
                    answered.result().answer(),
                    answered.result().intent(),
                    answered.generatedBy(),
                    LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
            );
            saveTreasurerAnswerPort.save(saved);
            return saved.id();
        } catch (RuntimeException exception) {
            log.warn(
                    "Failed to store treasurer answer for reporting. failureType={}",
                    exception.getClass().getSimpleName()
            );
            return null;
        }
    }

    private Answered answerFor(AskTreasurerCommand command) {
        InquiryParser.ParsedInquiry parsed = inquiryParser.parse(command.question());
        if (parsed.intent() == InquiryIntent.UNKNOWN) {
            return unmatchedAnswer(command.question());
        }
        if (parsed.intent() == InquiryIntent.GUIDE) {
            return textAnswer(InquiryIntent.GUIDE, TreasurerCardCopy.OCR_ANSWER, List.of());
        }

        String question = command.question();
        InquiryPeriod parsedPeriod = parsed.period();
        InquiryPeriod period = parsedPeriod == null ? InquiryPeriod.THIS_MONTH : parsedPeriod;
        Instant now = clock.instant();
        List<PersonalSettlement> all = treasurerSettlementQuery.loadMine();
        String titleHint = titleHintVerification.verify(all, parsed.titleHint());
        boolean titleDropped = parsed.titleHint() != null && titleHint == null;
        List<PersonalSettlement> mine = all.stream()
                .filter(settlement -> titleHintVerification.matchesTitle(settlement, titleHint))
                .toList();
        List<PersonalSettlement> filtered = mine.stream()
                .filter(settlement -> inRequestedPeriod(settlement, period, now))
                .toList();
        String scope = scopeLabel(period, titleHint, parsed.titleHint());

        return switch (parsed.intent()) {
            case SEND_SETTLEMENTS -> answerList(
                    question,
                    parsed.intent(),
                    period,
                    scope,
                    filtered.stream().filter(PersonalSettlement::isSend).filter(PersonalSettlement::isOutstanding).toList(),
                    "보낼 정산",
                    true,
                    now,
                    new TreasurerAnswerResult.Action(TreasurerAnswerActionType.SEND_LIST, "보낼 정산 확인하기"),
                    titleDropped
            );
            case RECEIVE_SETTLEMENTS -> answerList(
                    question,
                    parsed.intent(),
                    period,
                    scope,
                    filtered.stream().filter(PersonalSettlement::isReceive).filter(PersonalSettlement::isOutstanding).toList(),
                    "받을 정산",
                    true,
                    now,
                    new TreasurerAnswerResult.Action(TreasurerAnswerActionType.RECEIVE_LIST, "받을 정산 확인하기"),
                    titleDropped
            );
            case DUE_SETTLEMENTS -> answerList(
                    question,
                    parsed.intent(),
                    period,
                    scope,
                    mine.stream()
                            .filter(PersonalSettlement::isOutstanding)
                            .filter(settlement -> inDuePeriod(settlement, period, now))
                            .toList(),
                    "마감이 필요한 정산",
                    false,
                    now,
                    new TreasurerAnswerResult.Action(TreasurerAnswerActionType.STATUS_LIST, "정산 상태 확인하기"),
                    titleDropped
            );
            case SETTLEMENT_STATUS -> answerStatus(question, period, scope, filtered, titleDropped);
            case EXPENSE_SUMMARY -> answerList(
                    question,
                    parsed.intent(),
                    period,
                    scope,
                    filtered.stream().filter(PersonalSettlement::countsAsExpense).toList(),
                    "지출·정산",
                    false,
                    now,
                    new TreasurerAnswerResult.Action(TreasurerAnswerActionType.EXPENSE_LIST, "지출 내역 보기"),
                    titleDropped
            );
            case GUIDE, UNKNOWN -> throw new IllegalStateException("이미 처리됨");
        };
    }

    private Answered unmatchedAnswer(String question) {
        String answer = inquiryScopePolicy.classify(question) == InquiryScope.RELATED
                ? TreasurerCardCopy.CLARIFY_ANSWER
                : TreasurerCardCopy.UNKNOWN_ANSWER;
        return textAnswer(
                InquiryIntent.UNKNOWN,
                answer,
                treasurerCardCopy.suggestions().stream().map(this::toPrompt).toList()
        );
    }

    private Answered textAnswer(
            InquiryIntent intent,
            String answer,
            List<TreasurerAnswerResult.Prompt> prompts
    ) {
        return new Answered(
                new TreasurerAnswerResult(
                        null,
                        intent,
                        null,
                        null,
                        TreasurerAnswerView.TEXT,
                        answer,
                        null,
                        List.of(),
                        null,
                        List.of(),
                        prompts
                ),
                TreasurerAnswerGeneratedBy.SERVER
        );
    }

    private Answered answerList(
            String question,
            InquiryIntent intent,
            InquiryPeriod period,
            String scope,
            List<PersonalSettlement> items,
            String label,
            boolean remainingCopy,
            Instant now,
            TreasurerAnswerResult.Action action,
            boolean deterministicOnly
    ) {
        long total = items.stream().mapToLong(PersonalSettlement::amountOrZero).sum();
        String fallback = items.isEmpty()
                ? "%s %s은 없어요.".formatted(scope, label)
                : remainingCopy
                ? "%s %s은 %d건, 총 %,d원이 남아 있어요.".formatted(scope, label, items.size(), total)
                : "%s %s은 %d건, 총 %,d원이에요.".formatted(scope, label, items.size(), total);
        List<TreasurerAnswerResult.Item> cards = items.stream()
                .limit(CARD_LIMIT)
                .map(item -> toItem(treasurerCardCopy.item(item, now)))
                .toList();
        Phrased phrased = phrasedAnswer(
                question, intent, scope, fallback, total, items.size(), factsOf(cards),
                deterministicOnly);
        return new Answered(
                new TreasurerAnswerResult(
                        null,
                        intent,
                        period,
                        scope,
                        TreasurerAnswerView.LIST,
                        phrased.text(),
                        items.isEmpty() ? null : listFooter(items.size()),
                        cards,
                        null,
                        List.of(action),
                        List.of()
                ),
                phrased.generatedBy()
        );
    }

    private Answered answerStatus(
            String question,
            InquiryPeriod period,
            String scope,
            List<PersonalSettlement> items,
            boolean deterministicOnly
    ) {
        int sendCount = Math.toIntExact(items.stream()
                .filter(PersonalSettlement::isSend)
                .filter(PersonalSettlement::isOutstanding)
                .count());
        int receiveCount = Math.toIntExact(items.stream()
                .filter(PersonalSettlement::isReceive)
                .filter(PersonalSettlement::isOutstanding)
                .count());
        int completedCount = Math.toIntExact(items.stream().filter(item -> !item.isOpen()).count());
        long sendAmount = items.stream()
                .filter(PersonalSettlement::isSend)
                .filter(PersonalSettlement::isOutstanding)
                .mapToLong(PersonalSettlement::amountOrZero)
                .sum();
        long receiveAmount = items.stream()
                .filter(PersonalSettlement::isReceive)
                .filter(PersonalSettlement::isOutstanding)
                .mapToLong(PersonalSettlement::amountOrZero)
                .sum();
        String fallback = sendCount + receiveCount == 0
                ? "%s %s".formatted(scope, treasurerCardCopy.statusAnswer(0, 0))
                : "%s %s".formatted(scope, treasurerCardCopy.statusAnswer(sendCount, receiveCount));
        TreasurerCardCopy.StatusSummary summary = treasurerCardCopy.statusCard(
                items,
                switch (period) {
                    case LAST_MONTH -> "지난달 완료";
                    case THIS_WEEK -> "이번 주 완료";
                    case THIS_MONTH -> "이번 달 완료";
                }
        );
        Phrased phrased = phrasedAnswer(
                question,
                InquiryIntent.SETTLEMENT_STATUS,
                scope,
                fallback,
                sendAmount + receiveAmount,
                sendCount + receiveCount,
                List.of(
                        new TreasurerAnswerDraft.Item("내가 낼 돈", "SEND", sendAmount),
                        new TreasurerAnswerDraft.Item("내가 받을 돈", "RECEIVE", receiveAmount),
                        new TreasurerAnswerDraft.Item("완료된 정산", "COMPLETED", completedCount)
                ),
                deterministicOnly
        );
        return new Answered(
                new TreasurerAnswerResult(
                        null,
                        InquiryIntent.SETTLEMENT_STATUS,
                        period,
                        scope,
                        TreasurerAnswerView.SUMMARY,
                        phrased.text(),
                        null,
                        List.of(),
                        toSummary(summary),
                        List.of(new TreasurerAnswerResult.Action(
                                TreasurerAnswerActionType.STATUS_LIST, "정산 상태 확인하기")),
                        List.of()
                ),
                phrased.generatedBy()
        );
    }

    private Phrased phrasedAnswer(
            String question,
            InquiryIntent intent,
            String scope,
            String fallback,
            long totalAmount,
            int itemCount,
            List<TreasurerAnswerDraft.Item> items,
            boolean deterministicOnly
    ) {
        TreasurerAnswerDraft draft = new TreasurerAnswerDraft(
                question,
                intent,
                scope,
                fallback,
                totalAmount,
                itemCount,
                items
        );
        if (deterministicOnly) {
            // 이름으로 좁히려다 실패한 답변이다. LLM 문장을 붙이면 없는 정산을 있는 것처럼
            // 말할 여지가 생긴다. 서버가 계산한 문구만 내보낸다.
            return new Phrased(draft.fallbackAnswer(), TreasurerAnswerGeneratedBy.SERVER);
        }
        return generateTreasurerAnswerPort.generate(draft)
                .filter(text -> treasurerAnswerFactCheck.accepts(text, draft))
                .map(text -> new Phrased(text, TreasurerAnswerGeneratedBy.LLM))
                .orElseGet(() -> new Phrased(
                        draft.fallbackAnswer(), TreasurerAnswerGeneratedBy.SERVER));
    }

    /**
     * 답변과 그 문장을 누가 만들었는지. 신고를 확인할 때 LLM 문장인지 서버 문구인지 구분해야 한다.
     */
    private record Phrased(String text, TreasurerAnswerGeneratedBy generatedBy) {
    }

    private record Answered(TreasurerAnswerResult result, TreasurerAnswerGeneratedBy generatedBy) {
    }

    private List<TreasurerAnswerDraft.Item> factsOf(List<TreasurerAnswerResult.Item> items) {
        return items.stream()
                .map(item -> new TreasurerAnswerDraft.Item(
                        item.headline(),
                        item.direction().name(),
                        item.amount()
                ))
                .toList();
    }

    private TreasurerAnswerResult.Item toItem(TreasurerCardCopy.Card card) {
        return new TreasurerAnswerResult.Item(
                card.settlementId(),
                card.requestedDate(),
                card.headline(),
                card.subtitle(),
                card.direction(),
                card.amount(),
                card.marker(),
                card.allocationType(),
                card.status()
        );
    }

    private TreasurerAnswerResult.SummaryCard toSummary(TreasurerCardCopy.StatusSummary summary) {
        return new TreasurerAnswerResult.SummaryCard(
                toRow(summary.send()),
                toRow(summary.receive()),
                toRow(summary.completed())
        );
    }

    private TreasurerAnswerResult.SummaryRow toRow(TreasurerCardCopy.Row row) {
        return new TreasurerAnswerResult.SummaryRow(row.label(), row.detail(), row.value());
    }

    private TreasurerAnswerResult.Prompt toPrompt(TreasurerCardCopy.Chip chip) {
        return new TreasurerAnswerResult.Prompt(chip.type(), chip.label(), chip.question());
    }

    private String listFooter(int totalCount) {
        if (totalCount <= CARD_LIMIT) {
            return TreasurerCardCopy.LIST_FOOTER;
        }
        return "외 %d건이 더 있어요. %s".formatted(totalCount - CARD_LIMIT, TreasurerCardCopy.LIST_FOOTER);
    }

    private boolean inRequestedPeriod(PersonalSettlement settlement, InquiryPeriod period, Instant now) {
        return inPeriodDate(settlement.requestedAt(), period, now);
    }

    private boolean inDuePeriod(PersonalSettlement settlement, InquiryPeriod period, Instant now) {
        if (settlement.requestedAt() == null) {
            return false;
        }
        return inPeriodDate(settlement.requestedAt().plus(PersonalSettlement.STALE_AFTER), period, now);
    }

    private boolean inPeriodDate(LocalDateTime utcDateTime, InquiryPeriod period, Instant now) {
        if (utcDateTime == null) {
            return false;
        }
        LocalDate targetDate = utcDateTime.atOffset(ZoneOffset.UTC)
                .atZoneSameInstant(USER_ZONE)
                .toLocalDate();
        LocalDate today = LocalDate.ofInstant(now, USER_ZONE);
        if (period == InquiryPeriod.THIS_WEEK) {
            WeekFields weekFields = WeekFields.ISO;
            return targetDate.get(weekFields.weekBasedYear()) == today.get(weekFields.weekBasedYear())
                    && targetDate.get(weekFields.weekOfWeekBasedYear()) == today.get(weekFields.weekOfWeekBasedYear());
        }
        YearMonth target = YearMonth.from(today);
        if (period == InquiryPeriod.LAST_MONTH) {
            target = target.minusMonths(1);
        }
        return YearMonth.from(targetDate).equals(target);
    }

    /**
     * 질문에서 뽑은 제목이 사용자의 정산 중 하나라도 맞을 때만 필터로 쓴다.
     *
     * <p>맞는 정산이 없는데 그대로 거르면 결과가 0건이 되고 "그 정산은 없어요" 라는 단정이
     * 나간다. 실제로는 그 이름의 정산만 없고 다른 정산은 있을 수 있다. 돈에 대한 단정이라
     * 이름으로 좁히려던 의도를 버리고 전체에서 답한다.
     *
     * <p>기간은 보지 않는다. 이름은 맞고 기간만 다른 질문은 기존대로 기간으로 걸러야 한다.
     */
    private String scopeLabel(InquiryPeriod period, String appliedTitleHint, String parsedTitleHint) {
        String periodLabel = switch (period) {
            case LAST_MONTH -> "지난달 기준으로 조회했어요.";
            case THIS_WEEK -> "이번 주 기준으로 조회했어요.";
            case THIS_MONTH -> "이번 달 기준으로 조회했어요.";
        };
        if (appliedTitleHint != null) {
            return periodLabel + " '%s' 기준으로 좁혀서 조회했어요.".formatted(appliedTitleHint);
        }
        if (parsedTitleHint != null) {
            return periodLabel + " '%s'이(가) 포함된 정산을 찾지 못해 전체에서 조회했어요."
                    .formatted(parsedTitleHint);
        }
        return periodLabel;
    }
}
