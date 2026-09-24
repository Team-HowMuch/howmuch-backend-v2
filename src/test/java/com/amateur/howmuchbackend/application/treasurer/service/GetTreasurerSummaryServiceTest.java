package com.amateur.howmuchbackend.application.treasurer.service;

import com.amateur.howmuchbackend.domain.settlement.SettlementStatus;
import com.amateur.howmuchbackend.domain.treasurer.PersonalSettlement;
import com.amateur.howmuchbackend.domain.treasurer.SettlementDirection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GetTreasurerSummaryServiceTest {
    @Mock
    private TreasurerSettlementQuery treasurerSettlementQuery;

    @Test
    void summary_personalOpenAmounts_aggregated() {
        when(treasurerSettlementQuery.loadMine()).thenReturn(List.of(
                new PersonalSettlement(
                        UUID.randomUUID(),
                        "A",
                        SettlementDirection.SEND,
                        SettlementStatus.REQUESTED,
                        LocalDateTime.of(2026, 8, 1, 0, 0),
                        30_000L,
                        null
                ),
                new PersonalSettlement(
                        UUID.randomUUID(),
                        "B",
                        SettlementDirection.RECEIVE,
                        SettlementStatus.COMPLETED,
                        LocalDateTime.of(2026, 8, 1, 0, 0),
                        80_000L,
                        null
                )
        ));
        GetTreasurerSummaryService service = new GetTreasurerSummaryService(treasurerSettlementQuery);

        var result = service.getSummary();

        assertThat(result.sendCount()).isEqualTo(1);
        assertThat(result.sendAmount()).isEqualTo(30_000L);
        assertThat(result.completedCount()).isEqualTo(1);
        assertThat(result.message()).isEqualTo("보낼 정산 1건, 30,000원이 남아 있어요.");
    }

    @Test
    void summary_draftExcludedFromSendAndReceiveTotals() {
        when(treasurerSettlementQuery.loadMine()).thenReturn(List.of(
                new PersonalSettlement(
                        UUID.randomUUID(),
                        "작성 중",
                        SettlementDirection.RECEIVE,
                        SettlementStatus.DRAFT,
                        LocalDateTime.of(2026, 8, 1, 0, 0),
                        80_000L,
                        null
                )
        ));
        GetTreasurerSummaryService service = new GetTreasurerSummaryService(treasurerSettlementQuery);

        var result = service.getSummary();

        assertThat(result.inProgressCount()).isEqualTo(1);
        assertThat(result.receiveCount()).isZero();
        assertThat(result.receiveAmount()).isZero();
        assertThat(result.message()).isEqualTo("진행 중인 정산 1건이 있어요.");
    }
}
