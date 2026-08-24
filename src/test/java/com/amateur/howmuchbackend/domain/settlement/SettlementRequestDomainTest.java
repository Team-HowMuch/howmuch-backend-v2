package com.amateur.howmuchbackend.domain.settlement;

import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.settlement.status.SettlementStatusCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("정산 요청 Domain 규칙")
class SettlementRequestDomainTest {
    private static final UUID SETTLEMENT_ID = UUID.fromString("7c33b1fb-82f1-4a45-a834-a015a26b2ba6");
    private static final UUID PARTICIPANT_ID = UUID.fromString("bb97ee76-3a82-4ccc-b324-4a9974c8e1af");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 24, 10, 0);

    @Test
    @DisplayName("링크는 lowercase SHA-256 hash와 미래 만료 시각만 허용한다")
    void validatesLinkHashAndExpiration() {
        SettlementLink link = SettlementLink.active(
                SETTLEMENT_ID,
                "a".repeat(64),
                NOW.plusDays(30),
                NOW
        );

        assertThat(link.status()).isEqualTo(SettlementLinkStatus.ACTIVE);
        assertThat(link.isUsableAt(NOW.plusDays(29))).isTrue();
        assertThat(link.isUsableAt(NOW.plusDays(30))).isFalse();

        assertThatThrownBy(() -> SettlementLink.active(
                SETTLEMENT_ID, "A".repeat(64), NOW.plusDays(30), NOW
        )).isInstanceOfSatisfying(ApplicationException.class, exception ->
                assertThat(exception.getStatusCode()).isEqualTo(
                        SettlementStatusCode.INVALID_SETTLEMENT_LINK_TOKEN_HASH
                ));
        assertThatThrownBy(() -> SettlementLink.active(
                SETTLEMENT_ID, "a".repeat(64), NOW, NOW
        )).isInstanceOfSatisfying(ApplicationException.class, exception ->
                assertThat(exception.getStatusCode()).isEqualTo(
                        SettlementStatusCode.INVALID_SETTLEMENT_LINK_EXPIRATION
                ));
    }

    @Test
    @DisplayName("확정 Share는 1원 이상의 송금 의무만 생성한다")
    void requiresPositiveShareAmount() {
        assertThat(SettlementShare.create(
                SETTLEMENT_ID, PARTICIPANT_ID, 5_000, NOW
        ).amount()).isEqualTo(5_000);

        assertThatThrownBy(() -> SettlementShare.create(
                SETTLEMENT_ID, PARTICIPANT_ID, 0, NOW
        )).isInstanceOfSatisfying(ApplicationException.class, exception ->
                assertThat(exception.getStatusCode()).isEqualTo(
                        SettlementStatusCode.INVALID_SETTLEMENT_SHARE_AMOUNT
                ));
    }

    @Test
    @DisplayName("수금 계좌 스냅샷은 기관 코드와 원문 계좌번호·예금주를 정규화한다")
    void validatesReceivingAccountSnapshot() {
        SettlementReceivingAccountSnapshot snapshot = SettlementReceivingAccountSnapshot.create(
                SETTLEMENT_ID,
                7L,
                "0020",
                " 1002123456789 ",
                " 최수아 ",
                NOW
        );

        assertThat(snapshot.accountNumber()).isEqualTo("1002123456789");
        assertThat(snapshot.accountHolder()).isEqualTo("최수아");
        assertThatThrownBy(() -> SettlementReceivingAccountSnapshot.create(
                SETTLEMENT_ID, 7L, "20", "1002123456789", "최수아", NOW
        )).isInstanceOfSatisfying(ApplicationException.class, exception ->
                assertThat(exception.getStatusCode()).isEqualTo(
                        SettlementStatusCode.INVALID_SETTLEMENT_RECEIVING_ACCOUNT_SNAPSHOT
                ));
        assertThatThrownBy(() -> SettlementReceivingAccountSnapshot.create(
                SETTLEMENT_ID, 7L, "0020", "\u2003", "최수아", NOW
        )).isInstanceOf(ApplicationException.class);
    }
}
