package com.amateur.howmuchbackend.application.treasurer.service;

import com.amateur.howmuchbackend.domain.settlement.SettlementStatus;
import com.amateur.howmuchbackend.domain.treasurer.PersonalSettlement;
import com.amateur.howmuchbackend.domain.treasurer.SettlementDirection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GetTreasurerRecommendationsServiceTest {
    @Mock
    private TreasurerSettlementQuery treasurerSettlementQuery;

    @Test
    void recommendations_noPendingWork_returnsEmptyMessage() {
        when(treasurerSettlementQuery.loadMine()).thenReturn(List.of());
        GetTreasurerRecommendationsService service = service();

        var result = service.getRecommendations();

        assertThat(result.message()).isEqualTo("처리할 정산이 없어요.");
        assertThat(result.items()).isEmpty();
    }

    @Test
    void transferRecommendation_awaitingDepositSend_only() {
        when(treasurerSettlementQuery.loadMine()).thenReturn(List.of(
                new PersonalSettlement(
                        UUID.randomUUID(),
                        "요청됨",
                        SettlementDirection.SEND,
                        SettlementStatus.REQUESTED,
                        LocalDateTime.of(2026, 8, 10, 0, 0),
                        10_000L,
                        "요청자"
                ),
                new PersonalSettlement(
                        UUID.randomUUID(),
                        "입금대기",
                        SettlementDirection.SEND,
                        SettlementStatus.AWAITING_DEPOSIT,
                        LocalDateTime.of(2026, 8, 10, 0, 0),
                        12_000L,
                        "요청자"
                )
        ));
        GetTreasurerRecommendationsService service = service();

        var result = service.getRecommendations();

        assertThat(result.message()).isEqualTo("확인이 필요한 정산이 2건 있어요.");
        assertThat(result.items()).filteredOn(item -> "요청됨".equals(item.title()))
                .singleElement()
                .extracting(item -> item.actions().stream().map(action -> action.type()).toList())
                .isEqualTo(List.of("SETTLEMENT_DETAIL"));
        assertThat(result.items()).filteredOn(item -> "입금대기".equals(item.title()))
                .singleElement()
                .extracting(item -> item.actions().stream().map(action -> action.type()).toList())
                .isEqualTo(List.of("SETTLEMENT_DETAIL", "TRANSFER"));
    }

    @Test
    void excludesDraftFromRecommendations() {
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
        GetTreasurerRecommendationsService service = service();

        var result = service.getRecommendations();

        assertThat(result.message()).isEqualTo("처리할 정산이 없어요.");
        assertThat(result.items()).isEmpty();
    }

    private GetTreasurerRecommendationsService service() {
        return new GetTreasurerRecommendationsService(
                treasurerSettlementQuery,
                Clock.fixed(Instant.parse("2026-08-26T03:00:00Z"), ZoneOffset.UTC)
        );
    }
}
