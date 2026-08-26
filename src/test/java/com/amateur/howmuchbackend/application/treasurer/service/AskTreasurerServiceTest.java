package com.amateur.howmuchbackend.application.treasurer.service;

import com.amateur.howmuchbackend.application.port.in.treasurer.request.AskTreasurerRequest;
import com.amateur.howmuchbackend.application.treasurer.policy.InquiryParser;
import com.amateur.howmuchbackend.domain.settlement.SettlementStatus;
import com.amateur.howmuchbackend.domain.treasurer.InquiryIntent;
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
class AskTreasurerServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-26T03:00:00Z");

    @Mock
    private TreasurerSettlementQuery treasurerSettlementQuery;

    @Test
    void answersOpenSendSettlementsInCurrentMonth() {
        UUID id = UUID.fromString("11111111-1111-1111-1111-111111111111");
        when(treasurerSettlementQuery.loadMine()).thenReturn(List.of(
                new PersonalSettlement(
                        id,
                        "여행 모임",
                        SettlementDirection.SEND,
                        SettlementStatus.REQUESTED,
                        LocalDateTime.of(2026, 8, 10, 0, 0),
                        45_000L,
                        "요청자"
                ),
                new PersonalSettlement(
                        UUID.randomUUID(),
                        "여행 모임",
                        SettlementDirection.SEND,
                        SettlementStatus.COMPLETED,
                        LocalDateTime.of(2026, 8, 2, 0, 0),
                        9_000L,
                        "요청자"
                )
        ));
        AskTreasurerService service = new AskTreasurerService(
                treasurerSettlementQuery,
                new InquiryParser(),
                Clock.fixed(NOW, ZoneOffset.UTC)
        );

        var result = service.ask(new AskTreasurerRequest("이번 달 내가 보낼 정산은 얼마야?"));

        assertThat(result.intent()).isEqualTo(InquiryIntent.SEND_SETTLEMENTS.name());
        assertThat(result.answer()).contains("1건").contains("45,000원");
        assertThat(result.highlights()).extracting(item -> item.settlementId()).containsExactly(id);
        assertThat(result.scope()).contains("현재 월");
    }

    @Test
    void returnsExamplesWhenIntentIsUnknown() {
        AskTreasurerService service = new AskTreasurerService(
                treasurerSettlementQuery,
                new InquiryParser(),
                Clock.fixed(NOW, ZoneOffset.UTC)
        );

        var result = service.ask(new AskTreasurerRequest("푸딩은 어떻게 만드나요?"));

        assertThat(result.intent()).isEqualTo(InquiryIntent.UNKNOWN.name());
        assertThat(result.answer()).contains("이렇게 물어볼 수 있어요");
    }
}
