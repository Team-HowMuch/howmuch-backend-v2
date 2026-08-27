package com.amateur.howmuchbackend.application.treasurer.service;

import com.amateur.howmuchbackend.application.port.in.treasurer.AskTreasurerUseCase;
import com.amateur.howmuchbackend.application.port.in.treasurer.command.AskTreasurerCommand;
import com.amateur.howmuchbackend.application.port.in.treasurer.result.TreasurerAnswerResult;
import com.amateur.howmuchbackend.application.port.out.treasurer.GenerateTreasurerAnswerPort;
import com.amateur.howmuchbackend.application.treasurer.policy.InquiryParser;
import com.amateur.howmuchbackend.domain.treasurer.InquiryIntent;
import com.amateur.howmuchbackend.domain.treasurer.InquiryPeriod;
import com.amateur.howmuchbackend.domain.treasurer.PersonalSettlement;
import com.amateur.howmuchbackend.domain.treasurer.SettlementDirection;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class AskTreasurerService implements AskTreasurerUseCase {
    static final int HIGHLIGHT_LIMIT = 3;
    private static final ZoneId USER_ZONE = ZoneId.of("Asia/Seoul");
    private static final String UNKNOWN_ANSWER = """
            이렇게 물어볼 수 있어요.
            - 이번 달 내가 보낼 정산은 얼마야?
            - 여행 모임에서 받을 돈이 있어?
            - 이번 달 지출을 요약해줘""";

    private final TreasurerSettlementQuery treasurerSettlementQuery;
    private final InquiryParser inquiryParser;
    private final GenerateTreasurerAnswerPort generateTreasurerAnswerPort;
    private final Clock clock;

    @Override
    @Transactional(readOnly = true)
    public TreasurerAnswerResult ask(AskTreasurerCommand command) {
        InquiryParser.ParsedInquiry parsed = inquiryParser.parse(command.question());
        if (parsed.intent() == InquiryIntent.UNKNOWN) {
            return new TreasurerAnswerResult(
                    InquiryIntent.UNKNOWN.name(),
                    null,
                    null,
                    UNKNOWN_ANSWER,
                    List.of(),
                    List.of()
            );
        }

        String question = command.question();
        InquiryPeriod parsedPeriod = parsed.period();
        InquiryPeriod period = parsedPeriod == null ? InquiryPeriod.THIS_MONTH : parsedPeriod;
        List<PersonalSettlement> filtered = treasurerSettlementQuery.loadMine().stream()
                .filter(settlement -> inPeriod(settlement, period))
                .filter(settlement -> matchesTitle(settlement, parsed.titleHint()))
                .toList();

        String scope = scopeLabel(period, parsed.titleHint(), parsedPeriod == null);
        return switch (parsed.intent()) {
            case SEND_SETTLEMENTS -> answerList(
                    question,
                    parsed.intent(),
                    period,
                    scope,
                    filtered.stream().filter(PersonalSettlement::isSend).filter(PersonalSettlement::isOutstanding).toList(),
                    "보낼 정산",
                    new TreasurerAnswerResult.Action("SEND_LIST", "보낼 정산 확인하기")
            );
            case RECEIVE_SETTLEMENTS -> answerList(
                    question,
                    parsed.intent(),
                    period,
                    scope,
                    filtered.stream().filter(PersonalSettlement::isReceive).filter(PersonalSettlement::isOutstanding).toList(),
                    "받을 정산",
                    new TreasurerAnswerResult.Action("RECEIVE_LIST", "받을 정산 확인하기")
            );
            case SETTLEMENT_STATUS -> answerStatus(question, period, scope, filtered);
            case EXPENSE_SUMMARY -> answerExpense(
                    question,
                    period,
                    scope,
                    filtered.stream().filter(PersonalSettlement::countsAsExpense).toList()
            );
            case UNKNOWN -> throw new IllegalStateException("UNKNOWN은 이미 처리됨");
        };
    }

    private TreasurerAnswerResult answerList(
            String question,
            InquiryIntent intent,
            InquiryPeriod period,
            String scope,
            List<PersonalSettlement> items,
            String label,
            TreasurerAnswerResult.Action action
    ) {
        long total = items.stream().mapToLong(PersonalSettlement::amountOrZero).sum();
        String extra = items.size() > HIGHLIGHT_LIMIT ? " 외 %d건".formatted(items.size() - HIGHLIGHT_LIMIT) : "";
        String fallback = items.isEmpty()
                ? "%s %s은 없어요.".formatted(scope, label)
                : "%s %s은 %d건, 총 %,d원이에요.%s".formatted(scope, label, items.size(), total, extra);
        List<TreasurerAnswerResult.Highlight> highlights = highlights(items);
        return new TreasurerAnswerResult(
                intent.name(),
                period.name(),
                scope,
                phrasedAnswer(question, intent.name(), scope, fallback, total, items.size(), highlights),
                highlights,
                List.of(action)
        );
    }

    private TreasurerAnswerResult answerStatus(
            String question,
            InquiryPeriod period,
            String scope,
            List<PersonalSettlement> items
    ) {
        List<PersonalSettlement> openItems = items.stream().filter(PersonalSettlement::isOpen).toList();
        long openAmount = openItems.stream().mapToLong(PersonalSettlement::amountOrZero).sum();
        long done = items.size() - openItems.size();
        String fallback = "%s 진행 중인 정산 %d건, 완료된 정산 %d건이에요.".formatted(scope, openItems.size(), done);
        List<TreasurerAnswerResult.Highlight> highlights = highlights(openItems);
        return new TreasurerAnswerResult(
                InquiryIntent.SETTLEMENT_STATUS.name(),
                period.name(),
                scope,
                phrasedAnswer(
                        question,
                        InquiryIntent.SETTLEMENT_STATUS.name(),
                        scope,
                        fallback,
                        openAmount,
                        items.size(),
                        highlights
                ),
                highlights,
                List.of(new TreasurerAnswerResult.Action("STATUS_LIST", "정산 상태 확인하기"))
        );
    }

    private TreasurerAnswerResult answerExpense(
            String question,
            InquiryPeriod period,
            String scope,
            List<PersonalSettlement> items
    ) {
        long total = items.stream().mapToLong(PersonalSettlement::amountOrZero).sum();
        String fallback = items.isEmpty()
                ? "%s 조회된 지출·정산이 없어요.".formatted(scope)
                : "%s 정산·지출은 %d건, 총 %,d원이에요.".formatted(scope, items.size(), total);
        List<TreasurerAnswerResult.Highlight> highlights = highlights(items);
        return new TreasurerAnswerResult(
                InquiryIntent.EXPENSE_SUMMARY.name(),
                period.name(),
                scope,
                phrasedAnswer(question, InquiryIntent.EXPENSE_SUMMARY.name(), scope, fallback, total, items.size(), highlights),
                highlights,
                List.of(new TreasurerAnswerResult.Action("EXPENSE_LIST", "지출 내역 보기"))
        );
    }

    private String phrasedAnswer(
            String question,
            String intent,
            String scope,
            String fallback,
            long totalAmount,
            int itemCount,
            List<TreasurerAnswerResult.Highlight> highlights
    ) {
        return generateTreasurerAnswerPort.generate(new GenerateTreasurerAnswerPort.TreasurerAnswerDraft(
                question,
                intent,
                scope,
                fallback,
                totalAmount,
                itemCount,
                highlights.stream()
                        .map(item -> new GenerateTreasurerAnswerPort.TreasurerAnswerDraft.Item(
                                item.title(),
                                item.direction(),
                                item.amount()
                        ))
                        .toList()
        ));
    }

    private List<TreasurerAnswerResult.Highlight> highlights(List<PersonalSettlement> items) {
        return items.stream()
                .limit(HIGHLIGHT_LIMIT)
                .map(item -> new TreasurerAnswerResult.Highlight(
                        item.settlementId(),
                        item.title(),
                        item.direction() == SettlementDirection.SEND ? "SEND" : "RECEIVE",
                        item.amountOrZero()
                ))
                .toList();
    }

    private boolean inPeriod(PersonalSettlement settlement, InquiryPeriod period) {
        if (settlement.requestedAt() == null) {
            return false;
        }
        LocalDate requestedDate = settlement.requestedAt().atOffset(ZoneOffset.UTC)
                .atZoneSameInstant(USER_ZONE)
                .toLocalDate();
        YearMonth target = YearMonth.from(LocalDate.ofInstant(clock.instant(), USER_ZONE));
        if (period == InquiryPeriod.LAST_MONTH) {
            target = target.minusMonths(1);
        }
        return YearMonth.from(requestedDate).equals(target);
    }

    private boolean matchesTitle(PersonalSettlement settlement, String titleHint) {
        if (titleHint == null) {
            return true;
        }
        String title = settlement.title() == null ? "" : settlement.title().replaceAll("\\s+", "");
        return title.toLowerCase(Locale.ROOT).contains(titleHint.toLowerCase(Locale.ROOT));
    }

    private String scopeLabel(InquiryPeriod period, String titleHint, boolean periodInferred) {
        String periodLabel = period == InquiryPeriod.LAST_MONTH
                ? "지난달 기준으로 조회했어요."
                : periodInferred
                ? "기간을 지정하지 않아 이번 달만 조회했어요."
                : "이번 달 기준으로 조회했어요.";
        if (titleHint == null) {
            return periodLabel;
        }
        return periodLabel + " '%s'이(가) 포함된 정산을 찾았어요.".formatted(titleHint);
    }
}
