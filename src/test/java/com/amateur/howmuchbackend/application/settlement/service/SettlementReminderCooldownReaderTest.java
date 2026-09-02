package com.amateur.howmuchbackend.application.settlement.service;

import com.amateur.howmuchbackend.application.port.out.settlement.SettlementReminderCooldownPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("정산 독촉 쿨다운 조회")
class SettlementReminderCooldownReaderTest {
    private static final UUID SETTLEMENT_ID = UUID.fromString(
            "44c9323d-6a65-4a93-aeaa-ef8f24341c8a"
    );
    private static final UUID FIRST_PARTICIPANT_ID = UUID.fromString(
            "09ac164a-35d6-4549-bff4-b7b4c375d2aa"
    );
    private static final UUID SECOND_PARTICIPANT_ID = UUID.fromString(
            "ee3c8674-7d99-4fe3-bf21-154ed54b532a"
    );
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 2, 3, 0);

    @Mock private SettlementReminderCooldownPort cooldownPort;
    @Mock private Clock clock;

    private SettlementReminderCooldownReader reader;

    @BeforeEach
    void setUp() {
        reader = new SettlementReminderCooldownReader(cooldownPort, clock);
    }

    @Test
    @DisplayName("TTL 조회 직후 시각을 기준으로 가장 늦은 만료 시각을 반환한다")
    void returnsLatestNextAvailableAt() {
        when(cooldownPort.findRemainingCooldown(SETTLEMENT_ID, FIRST_PARTICIPANT_ID))
                .thenReturn(Optional.of(Duration.ofMinutes(10)));
        when(cooldownPort.findRemainingCooldown(SETTLEMENT_ID, SECOND_PARTICIPANT_ID))
                .thenReturn(Optional.of(Duration.ofMinutes(30)));
        when(clock.instant()).thenReturn(
                NOW.toInstant(ZoneOffset.UTC),
                NOW.plusMinutes(5).toInstant(ZoneOffset.UTC)
        );

        Optional<LocalDateTime> result = reader.findLatestNextAvailableAt(
                SETTLEMENT_ID,
                List.of(FIRST_PARTICIPANT_ID, SECOND_PARTICIPANT_ID, FIRST_PARTICIPANT_ID)
        );

        assertThat(result).contains(NOW.plusMinutes(35));
        InOrder inOrder = inOrder(cooldownPort, clock);
        inOrder.verify(cooldownPort)
                .findRemainingCooldown(SETTLEMENT_ID, FIRST_PARTICIPANT_ID);
        inOrder.verify(clock).instant();
        inOrder.verify(cooldownPort)
                .findRemainingCooldown(SETTLEMENT_ID, SECOND_PARTICIPANT_ID);
        inOrder.verify(clock).instant();
    }

    @Test
    @DisplayName("활성 쿨다운이 없으면 다음 가능 시각을 반환하지 않는다")
    void returnsEmptyWithoutActiveCooldown() {
        when(cooldownPort.findRemainingCooldown(SETTLEMENT_ID, FIRST_PARTICIPANT_ID))
                .thenReturn(Optional.empty());
        when(cooldownPort.findRemainingCooldown(SETTLEMENT_ID, SECOND_PARTICIPANT_ID))
                .thenReturn(Optional.of(Duration.ZERO));

        Optional<LocalDateTime> result = reader.findLatestNextAvailableAt(
                SETTLEMENT_ID,
                List.of(FIRST_PARTICIPANT_ID, SECOND_PARTICIPANT_ID)
        );

        assertThat(result).isEmpty();
        verifyNoInteractions(clock);
    }
}
