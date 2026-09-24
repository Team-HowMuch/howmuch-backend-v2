package com.amateur.howmuchbackend.domain.settlement;

import com.amateur.howmuchbackend.common.exception.ApplicationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("정산 링크 참여 문맥 Domain")
class SettlementParticipantContextTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 24, 12, 0);

    @Test
    @DisplayName("링크 hash와 정산·참여자를 묶은 불투명 UUID token을 발급한다")
    void participantContext_linkedIds_opaqueTokenIssued() {
        UUID settlementId = UUID.randomUUID();
        UUID participantId = UUID.randomUUID();

        SettlementParticipantContext context = SettlementParticipantContext.issue(
                "a".repeat(64), settlementId, participantId, NOW, NOW.plusMinutes(30)
        );

        assertThat(context.token()).isEqualTo(context.id().toString());
        assertThat(context.settlementId()).isEqualTo(settlementId);
        assertThat(context.participantId()).isEqualTo(participantId);
        assertThat(context.expiresAt()).isEqualTo(NOW.plusMinutes(30));
    }

    @Test
    @DisplayName("만료 시각이 생성 시각보다 늦지 않으면 거부한다")
    void participantContext_expirationNotAfterCreation_rejected() {
        assertThatThrownBy(() -> SettlementParticipantContext.issue(
                "a".repeat(64), UUID.randomUUID(), UUID.randomUUID(), NOW, NOW
        )).isInstanceOf(ApplicationException.class);
    }
}
