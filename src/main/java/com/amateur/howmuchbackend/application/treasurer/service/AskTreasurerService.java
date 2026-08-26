package com.amateur.howmuchbackend.application.treasurer.service;

import com.amateur.howmuchbackend.application.port.in.treasurer.AskTreasurerUseCase;
import com.amateur.howmuchbackend.application.port.in.treasurer.request.AskTreasurerRequest;
import com.amateur.howmuchbackend.application.port.in.treasurer.response.TreasurerAnswerResponse;
import com.amateur.howmuchbackend.application.treasurer.policy.InquiryParser;
import com.amateur.howmuchbackend.domain.treasurer.InquiryIntent;
import com.amateur.howmuchbackend.domain.treasurer.InquiryPeriod;
import com.amateur.howmuchbackend.domain.treasurer.PersonalSettlement;
import com.amateur.howmuchbackend.domain.treasurer.SettlementDirection;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

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
    private final Clock clock;

    @Override
    public TreasurerAnswerResponse ask(AskTreasurerRequest request) {
        InquiryParser.ParsedInquiry parsed = inquiryParser.parse(request.question());
        if (parsed.intent() == InquiryIntent.UNKNOWN) {
            return new TreasurerAnswerResponse(
                    InquiryIntent.UNKNOWN.name(),
                    null,
                    null,
                    UNKNOWN_ANSWER,
                    List.of(),
                    List.of()
            );
        }

        InquiryPeriod period = parsed.period() == null ? InquiryPeriod.THIS_MONTH : parsed.period();
        List<PersonalSettlement> filtered = treasurerSettlementQuery.loadMine().stream()
                .filter(settlement -> inPeriod(settlement, period))
                .filter(settlement -> matchesTitle(settlement, parsed.titleHint()))
                .toList();

        String scope = scopeLabel(period, parsed.titleHint());
        return switch (parsed.intent()) {
            case SEND_SETTLEMENTS -> answerList(
                    parsed.intent(),
                    period,
                    scope,
                    filtered.stream().filter(PersonalSettlement::isSend).filter(PersonalSettlement::isOpen).toList(),
                    "보낼 정산",
                    new TreasurerAnswerResponse.Action("SEND_LIST", "보낼 정산 확인하기")
            );
            case RECEIVE_SETTLEMENTS -> answerList(
                    parsed.intent(),
                    period,
                    scope,
                    filtered.stream().filter(PersonalSettlement::isReceive).filter(PersonalSettlement::isOpen).toList(),
                    "받을 정산",
                    new TreasurerAnswerResponse.Action("RECEIVE_LIST", "받을 정산 확인하기")
            );
            case SETTLEMENT_STATUS -> answerStatus(period, scope, filtered);
            case EXPENSE_SUMMARY -> answerExpense(period, scope, filtered);
            case UNKNOWN -> throw new IllegalStateException("UNKNOWN은 이미 처리됨");
        };
    }

    private TreasurerAnswerResponse answerList(
            InquiryIntent intent,
            InquiryPeriod period,
            String scope,
            List<PersonalSettlement> items,
            String label,
            TreasurerAnswerResponse.Action action
    ) {
        long total = items.stream().mapToLong(PersonalSettlement::amountOrZero).sum();
        String extra = items.size() > HIGHLIGHT_LIMIT ? " 외 %d건".formatted(items.size() - HIGHLIGHT_LIMIT) : "";
        String answer = items.isEmpty()
                ? "%s %s은 없어요.".formatted(scope, label)
                : "%s %s은 %d건, 총 %,d원이에요.%s".formatted(scope, label, items.size(), total, extra);
        return new TreasurerAnswerResponse(
                intent.name(),
                period.name(),
                scope,
                answer,
                highlights(items),
                List.of(action)
        );
    }

    private TreasurerAnswerResponse answerStatus(InquiryPeriod period, String scope, List<PersonalSettlement> items) {
        long open = items.stream().filter(PersonalSettlement::isOpen).count();
        long done = items.size() - open;
        String answer = "%s 진행 중인 정산 %d건, 완료된 정산 %d건이에요.".formatted(scope, open, done);
        return new TreasurerAnswerResponse(
                InquiryIntent.SETTLEMENT_STATUS.name(),
                period.name(),
                scope,
                answer,
                highlights(items.stream().filter(PersonalSettlement::isOpen).toList()),
                List.of(new TreasurerAnswerResponse.Action("STATUS_LIST", "정산 상태 확인하기"))
        );
    }

    private TreasurerAnswerResponse answerExpense(InquiryPeriod period, String scope, List<PersonalSettlement> items) {
        long total = items.stream().mapToLong(PersonalSettlement::amountOrZero).sum();
        String answer = items.isEmpty()
                ? "%s 조회된 지출·정산이 없어요.".formatted(scope)
                : "%s 정산·지출은 %d건, 총 %,d원이에요.".formatted(scope, items.size(), total);
        return new TreasurerAnswerResponse(
                InquiryIntent.EXPENSE_SUMMARY.name(),
                period.name(),
                scope,
                answer,
                highlights(items),
                List.of(new TreasurerAnswerResponse.Action("EXPENSE_LIST", "지출 내역 보기"))
        );
    }

    private List<TreasurerAnswerResponse.Highlight> highlights(List<PersonalSettlement> items) {
        return items.stream()
                .limit(HIGHLIGHT_LIMIT)
                .map(item -> new TreasurerAnswerResponse.Highlight(
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

    private String scopeLabel(InquiryPeriod period, String titleHint) {
        String periodLabel = period == InquiryPeriod.LAST_MONTH ? "지난달 기준으로 조회했어요." : "현재 월 기준으로 조회했어요.";
        if (titleHint == null) {
            return periodLabel;
        }
        return periodLabel + " '%s'이(가) 포함된 정산을 찾았어요.".formatted(titleHint);
    }
}
