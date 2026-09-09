package com.amateur.howmuchbackend.application.treasurer.policy;

import com.amateur.howmuchbackend.domain.settlement.SettlementAllocationType;
import com.amateur.howmuchbackend.domain.settlement.SettlementStatus;
import com.amateur.howmuchbackend.domain.treasurer.InquiryIntent;
import com.amateur.howmuchbackend.domain.treasurer.PersonalSettlement;
import com.amateur.howmuchbackend.domain.treasurer.SettlementDirection;
import com.amateur.howmuchbackend.domain.treasurer.TreasurerCardMarker;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

@Component
public class TreasurerCardCopy {
    public static final String GREETING = "안녕하세요. 어떤 정산이 궁금하세요?";
    public static final String LIST_FOOTER = "누르면 해당되는 정산 상세로 이동해요!";
    public static final String OCR_ANSWER = """
            OCR로 영수증을 읽어 정산을 만드는 기능은 아직 준비 중이에요.
            지금은 정산 만들기에서 금액을 직접 입력해 주세요.""";
    public static final String UNKNOWN_ANSWER = """
            이렇게 물어볼 수 있어요.
            - 이번 달 내가 보낼 정산은 얼마야?
            - 여행 모임에서 받을 돈이 있어?
            - 이번 달 지출을 요약해줘""";
    public static final String CLARIFY_ANSWER = """
            정산과 관련된 질문으로 이해했어요.
            어떤 정산을 확인하고 싶은지 알려주시면 더 정확하게 도와드릴게요.""";
    private static final ZoneId USER_ZONE = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter DATE_LABEL = DateTimeFormatter.ofPattern("yyyy.MM.dd");

    public List<Chip> quickActions() {
        return List.of(
                new Chip(InquiryIntent.SEND_SETTLEMENTS, "보낼 정산", "보낼 정산"),
                new Chip(InquiryIntent.RECEIVE_SETTLEMENTS, "받을 정산", "받을 정산"),
                new Chip(InquiryIntent.SETTLEMENT_STATUS, "정산 상태", "정산 상태")
        );
    }

    public List<Chip> suggestions() {
        return List.of(
                new Chip(InquiryIntent.SETTLEMENT_STATUS, "이번 주에 정산 있어?", "이번 주에 정산 있어?"),
                new Chip(InquiryIntent.DUE_SETTLEMENTS, "이번 주에 마감해야 할 정산 알려줘", "이번 주에 마감해야 할 정산 알려줘"),
                new Chip(InquiryIntent.GUIDE, "OCR 정산은 어떻게 해?", "OCR 정산은 어떻게 해?"),
                new Chip(InquiryIntent.RECEIVE_SETTLEMENTS, "나한테 아직 송금 안 한 사람 누구야?", "나한테 아직 송금 안 한 사람 누구야?")
        );
    }

    public Card item(PersonalSettlement settlement, Instant now) {
        String date = settlement.requestedAt() == null
                ? null
                : settlement.requestedAt().atOffset(ZoneOffset.UTC)
                        .atZoneSameInstant(USER_ZONE)
                        .toLocalDate()
                        .format(DATE_LABEL);
        if (settlement.isSend()) {
            String requester = settlement.counterpartName() == null || settlement.counterpartName().isBlank()
                    ? "요청자"
                    : settlement.counterpartName();
            if (!settlement.isOutstanding()) {
                return new Card(
                        settlement.settlementId(),
                        date,
                        "%,d원 지출".formatted(settlement.amountOrZero()),
                        "%s 요청 - 완료".formatted(requester),
                        SettlementDirection.SEND,
                        settlement.amountOrZero(),
                        TreasurerCardMarker.NONE,
                        settlement.allocationType(),
                        settlement.status()
                );
            }
            return new Card(
                    settlement.settlementId(),
                    date,
                    "%,d원 내야 함".formatted(settlement.amountOrZero()),
                    "%s 요청 - 미납".formatted(requester),
                    SettlementDirection.SEND,
                    settlement.amountOrZero(),
                    settlement.isStale(now) ? TreasurerCardMarker.ATTENTION : TreasurerCardMarker.NONE,
                    settlement.allocationType(),
                    settlement.status()
            );
        }
        int payers = Math.max(settlement.payerCount(), 1);
        return new Card(
                settlement.settlementId(),
                date,
                "%,d원을 %d명이서 정산 중".formatted(settlement.amountOrZero(), payers),
                "총 %d명 중 %d명 완료".formatted(payers, settlement.completedPayerCount()),
                SettlementDirection.RECEIVE,
                settlement.amountOrZero(),
                TreasurerCardMarker.MORE,
                settlement.allocationType(),
                settlement.status()
        );
    }

    public StatusSummary statusCard(List<PersonalSettlement> items, String completedDetail) {
        List<PersonalSettlement> outstandingSend = items.stream()
                .filter(PersonalSettlement::isSend)
                .filter(PersonalSettlement::isOutstanding)
                .toList();
        List<PersonalSettlement> outstandingReceive = items.stream()
                .filter(PersonalSettlement::isReceive)
                .filter(PersonalSettlement::isOutstanding)
                .toList();
        long sendAmount = outstandingSend.stream().mapToLong(PersonalSettlement::amountOrZero).sum();
        long receiveAmount = outstandingReceive.stream().mapToLong(PersonalSettlement::amountOrZero).sum();
        int unpaidPeople = outstandingReceive.stream().mapToInt(PersonalSettlement::unpaidPayerCount).sum();
        int completedCount = Math.toIntExact(items.stream().filter(item -> !item.isOpen()).count());
        return new StatusSummary(
                new Row("내가 낼 돈", "미납 정산 %d건".formatted(outstandingSend.size()), "%,d원".formatted(sendAmount)),
                new Row(
                        "내가 받을 돈",
                        "진행 중 %d건 ∙ %d명 미납".formatted(outstandingReceive.size(), unpaidPeople),
                        "%,d원".formatted(receiveAmount)
                ),
                new Row("완료된 정산", completedDetail, "%d건".formatted(completedCount))
        );
    }

    public String statusAnswer(int sendCount, int receiveCount) {
        int total = sendCount + receiveCount;
        if (total == 0) {
            return "현재 처리할 정산이 없어요.";
        }
        return "아직 처리할 정산이 %d건 있어요. 내가 낼 정산 %d건, 받을 정산 %d건이에요."
                .formatted(total, sendCount, receiveCount);
    }

    public record Chip(InquiryIntent type, String label, String question) {
    }

    public record Card(
            UUID settlementId,
            String requestedDate,
            String headline,
            String subtitle,
            SettlementDirection direction,
            long amount,
            TreasurerCardMarker marker,
            SettlementAllocationType allocationType,
            SettlementStatus status
    ) {
    }

    public record StatusSummary(Row send, Row receive, Row completed) {
    }

    public record Row(String label, String detail, String value) {
    }
}
