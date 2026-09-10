package com.amateur.howmuchbackend.application.treasurer.service;

import com.amateur.howmuchbackend.application.port.in.treasurer.AskTreasurerUseCase;
import com.amateur.howmuchbackend.application.port.in.treasurer.command.AskTreasurerCommand;
import com.amateur.howmuchbackend.application.port.in.treasurer.result.TreasurerAnswerResult;
import com.amateur.howmuchbackend.application.port.out.treasurer.GenerateTreasurerAnswerPort;
import com.amateur.howmuchbackend.application.treasurer.model.TreasurerAnswerDraft;
import com.amateur.howmuchbackend.application.treasurer.policy.InquiryParser;
import com.amateur.howmuchbackend.application.treasurer.policy.TreasurerAnswerFactCheck;
import com.amateur.howmuchbackend.application.treasurer.policy.TreasurerCardCopy;
import com.amateur.howmuchbackend.domain.treasurer.InquiryIntent;
import com.amateur.howmuchbackend.domain.treasurer.InquiryPeriod;
import com.amateur.howmuchbackend.domain.treasurer.PersonalSettlement;
import com.amateur.howmuchbackend.domain.treasurer.TreasurerAnswerActionType;
import com.amateur.howmuchbackend.domain.treasurer.TreasurerAnswerView;
import lombok.RequiredArgsConstructor;
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

@Service
@RequiredArgsConstructor
public class AskTreasurerService implements AskTreasurerUseCase {
    static final int CARD_LIMIT = 20;
    private static final ZoneId USER_ZONE = ZoneId.of("Asia/Seoul");

    private final TreasurerSettlementQuery treasurerSettlementQuery;
    private final InquiryParser inquiryParser;
    private final GenerateTreasurerAnswerPort generateTreasurerAnswerPort;
    private final TreasurerAnswerFactCheck treasurerAnswerFactCheck;
    private final TreasurerCardCopy treasurerCardCopy;
    private final Clock clock;

    @Override
    @Transactional(readOnly = true)
    public TreasurerAnswerResult ask(AskTreasurerCommand command) {
        InquiryParser.ParsedInquiry parsed = inquiryParser.parse(command.question());
        if (parsed.intent() == InquiryIntent.UNKNOWN) {
            return textAnswer(
                    InquiryIntent.UNKNOWN,
                    TreasurerCardCopy.UNKNOWN_ANSWER,
                    treasurerCardCopy.suggestions().stream().map(this::toPrompt).toList()
            );
        }
        if (parsed.intent() == InquiryIntent.GUIDE) {
            return textAnswer(InquiryIntent.GUIDE, TreasurerCardCopy.OCR_ANSWER, List.of());
        }

        String question = command.question();
        InquiryPeriod parsedPeriod = parsed.period();
        InquiryPeriod period = parsedPeriod == null ? InquiryPeriod.THIS_MONTH : parsedPeriod;
        Instant now = clock.instant();
        List<PersonalSettlement> mine = treasurerSettlementQuery.loadMine().stream()
                .filter(settlement -> matchesTitle(settlement, parsed.titleHint()))
                .toList();
        List<PersonalSettlement> filtered = mine.stream()
                .filter(settlement -> inRequestedPeriod(settlement, period, now))
                .toList();
        String scope = scopeLabel(period, parsed.titleHint());

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
                    new TreasurerAnswerResult.Action(TreasurerAnswerActionType.SEND_LIST, "보낼 정산 확인하기")
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
                    new TreasurerAnswerResult.Action(TreasurerAnswerActionType.RECEIVE_LIST, "받을 정산 확인하기")
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
                    new TreasurerAnswerResult.Action(TreasurerAnswerActionType.STATUS_LIST, "정산 상태 확인하기")
            );
            case SETTLEMENT_STATUS -> answerStatus(question, period, scope, filtered);
            case EXPENSE_SUMMARY -> answerList(
                    question,
                    parsed.intent(),
                    period,
                    scope,
                    filtered.stream().filter(PersonalSettlement::countsAsExpense).toList(),
                    "지출·정산",
                    false,
                    now,
                    new TreasurerAnswerResult.Action(TreasurerAnswerActionType.EXPENSE_LIST, "지출 내역 보기")
            );
            case GUIDE, UNKNOWN -> throw new IllegalStateException("이미 처리됨");
        };
    }

    private TreasurerAnswerResult textAnswer(
            InquiryIntent intent,
            String answer,
            List<TreasurerAnswerResult.Prompt> prompts
    ) {
        return new TreasurerAnswerResult(
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
        );
    }

    private TreasurerAnswerResult answerList(
            String question,
            InquiryIntent intent,
            InquiryPeriod period,
            String scope,
            List<PersonalSettlement> items,
            String label,
            boolean remainingCopy,
            Instant now,
            TreasurerAnswerResult.Action action
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
        return new TreasurerAnswerResult(
                intent,
                period,
                scope,
                TreasurerAnswerView.LIST,
                phrasedAnswer(question, intent, scope, fallback, total, items.size(), factsOf(cards)),
                items.isEmpty() ? null : listFooter(items.size()),
                cards,
                null,
                List.of(action),
                List.of()
        );
    }

    private TreasurerAnswerResult answerStatus(
            String question,
            InquiryPeriod period,
            String scope,
            List<PersonalSettlement> items
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
        return new TreasurerAnswerResult(
                InquiryIntent.SETTLEMENT_STATUS,
                period,
                scope,
                TreasurerAnswerView.SUMMARY,
                phrasedAnswer(
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
                        )
                ),
                null,
                List.of(),
                toSummary(summary),
                List.of(new TreasurerAnswerResult.Action(TreasurerAnswerActionType.STATUS_LIST, "정산 상태 확인하기")),
                List.of()
        );
    }

    private String phrasedAnswer(
            String question,
            InquiryIntent intent,
            String scope,
            String fallback,
            long totalAmount,
            int itemCount,
            List<TreasurerAnswerDraft.Item> items
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
        return generateTreasurerAnswerPort.generate(draft)
                .filter(text -> treasurerAnswerFactCheck.accepts(text, draft))
                .orElse(draft.fallbackAnswer());
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

    private boolean matchesTitle(PersonalSettlement settlement, String titleHint) {
        if (titleHint == null) {
            return true;
        }
        String title = settlement.title() == null ? "" : settlement.title().replaceAll("\\s+", "");
        return title.toLowerCase(Locale.ROOT).contains(titleHint.toLowerCase(Locale.ROOT));
    }

    private String scopeLabel(InquiryPeriod period, String titleHint) {
        String periodLabel = switch (period) {
            case LAST_MONTH -> "지난달 기준으로 조회했어요.";
            case THIS_WEEK -> "이번 주 기준으로 조회했어요.";
            case THIS_MONTH -> "이번 달 기준으로 조회했어요.";
        };
        if (titleHint == null) {
            return periodLabel;
        }
        return periodLabel + " '%s'이(가) 포함된 정산을 찾았어요.".formatted(titleHint);
    }
}
