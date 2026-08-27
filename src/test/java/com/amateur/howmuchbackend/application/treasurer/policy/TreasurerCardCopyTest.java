package com.amateur.howmuchbackend.application.treasurer.policy;

import com.amateur.howmuchbackend.domain.settlement.SettlementStatus;
import com.amateur.howmuchbackend.domain.treasurer.PersonalSettlement;
import com.amateur.howmuchbackend.domain.treasurer.SettlementDirection;
import com.amateur.howmuchbackend.domain.treasurer.TreasurerCardMarker;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TreasurerCardCopyTest {
    private final TreasurerCardCopy copy = new TreasurerCardCopy();

    @Test
    void buildsSendAndReceiveListCopy() {
        Instant now = Instant.parse("2026-08-26T00:00:00Z");
        PersonalSettlement send = new PersonalSettlement(
                UUID.randomUUID(),
                "여행",
                SettlementDirection.SEND,
                SettlementStatus.REQUESTED,
                LocalDateTime.of(2026, 8, 13, 0, 0),
                200L,
                "빵장"
        );
        PersonalSettlement receive = new PersonalSettlement(
                UUID.randomUUID(),
                "회식",
                SettlementDirection.RECEIVE,
                SettlementStatus.AWAITING_DEPOSIT,
                LocalDateTime.of(2026, 8, 13, 0, 0),
                600L,
                "나",
                3,
                1
        );

        assertThat(copy.item(send, now).headline()).isEqualTo("200원 내야 함");
        assertThat(copy.item(send, now).subtitle()).isEqualTo("빵장 요청 - 미납");
        assertThat(copy.item(receive, now).headline()).isEqualTo("600원을 3명이서 정산 중");
        assertThat(copy.item(receive, now).subtitle()).isEqualTo("총 3명 중 1명 완료");
        assertThat(copy.item(receive, now).marker()).isEqualTo(TreasurerCardMarker.MORE);
    }

    @Test
    void completedSendExpenseIsNotUnpaid() {
        Instant now = Instant.parse("2026-08-26T00:00:00Z");
        PersonalSettlement completed = new PersonalSettlement(
                UUID.randomUUID(),
                "회식",
                SettlementDirection.SEND,
                SettlementStatus.COMPLETED,
                LocalDateTime.of(2026, 8, 2, 0, 0),
                9_000L,
                "요청자"
        );

        assertThat(copy.item(completed, now).headline()).isEqualTo("9,000원 지출");
        assertThat(copy.item(completed, now).subtitle()).isEqualTo("요청자 요청 - 완료");
        assertThat(copy.item(completed, now).marker()).isEqualTo(TreasurerCardMarker.NONE);
    }

    @Test
    void buildsStatusSummaryCard() {
        var card = copy.statusCard(
                List.of(
                        new PersonalSettlement(
                                UUID.randomUUID(), "a", SettlementDirection.SEND, SettlementStatus.REQUESTED,
                                LocalDateTime.of(2026, 8, 1, 0, 0), 45_000L, "요청자"
                        ),
                        new PersonalSettlement(
                                UUID.randomUUID(), "b", SettlementDirection.RECEIVE, SettlementStatus.AWAITING_DEPOSIT,
                                LocalDateTime.of(2026, 8, 1, 0, 0), 45_000L, "나", 5, 0
                        ),
                        new PersonalSettlement(
                                UUID.randomUUID(), "c", SettlementDirection.SEND, SettlementStatus.COMPLETED,
                                LocalDateTime.of(2026, 8, 1, 0, 0), 1L, "요청자"
                        )
                ),
                "이번 달 완료"
        );

        assertThat(card.send().detail()).isEqualTo("미납 정산 1건");
        assertThat(card.receive().detail()).isEqualTo("진행 중 1건 ∙ 5명 미납");
        assertThat(card.completed().value()).isEqualTo("1건");
        assertThat(copy.statusAnswer(3, 3)).isEqualTo("아직 처리할 정산이 6건 있어요. 내가 낼 정산 3건, 받을 정산 3건이에요.");
    }
}
