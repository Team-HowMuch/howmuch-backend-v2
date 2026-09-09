package com.amateur.howmuchbackend.adapter.out.persistence.settlement;

import com.amateur.howmuchbackend.adapter.out.persistence.settlement.repository.SettlementJpaRepository;
import com.amateur.howmuchbackend.application.port.out.treasurer.LoadTreasurerSettlementsPort.TreasurerSettlementRecord;
import com.amateur.howmuchbackend.domain.settlement.SettlementAllocationType;
import com.amateur.howmuchbackend.domain.treasurer.SettlementDirection;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("총무 정산 매핑")
class TreasurerSettlementPersistenceAdapterTest {
    private static final Long USER_ID = 1L;

    @Mock
    private SettlementJpaRepository settlementJpaRepository;

    @Test
    @DisplayName("낼 정산에 내 몫이 아직 배분되지 않았으면 금액을 null로 담는다")
    void keepsNullAmountWhenMyShareIsNotAllocatedYet() {
        SettlementJpaRepository.TreasurerSettlementProjection projection =
                mock(SettlementJpaRepository.TreasurerSettlementProjection.class);
        when(projection.getStatus()).thenReturn("REQUESTED");
        when(projection.getAmountToPay()).thenReturn(null);
        when(settlementJpaRepository.findAllForTreasurer(USER_ID, "RECEIVE")).thenReturn(List.of());
        when(settlementJpaRepository.findAllForTreasurer(USER_ID, "PAY")).thenReturn(List.of(projection));

        TreasurerSettlementPersistenceAdapter adapter =
                new TreasurerSettlementPersistenceAdapter(settlementJpaRepository);

        List<TreasurerSettlementRecord> records = adapter.findAll(USER_ID);

        assertThat(records).singleElement().satisfies(record -> {
            assertThat(record.direction()).isEqualTo(SettlementDirection.SEND);
            assertThat(record.amount()).isNull();
            assertThat(record.allocationType()).isNull();
        });
    }

    @Test
    @DisplayName("배분 유형이 있으면 도메인 타입으로 옮긴다")
    void mapsAllocationTypeWhenPresent() {
        SettlementJpaRepository.TreasurerSettlementProjection projection =
                mock(SettlementJpaRepository.TreasurerSettlementProjection.class);
        when(projection.getStatus()).thenReturn("REQUESTED");
        when(projection.getAllocationType()).thenReturn("ITEM_ALLOCATION");
        when(settlementJpaRepository.findAllForTreasurer(USER_ID, "RECEIVE")).thenReturn(List.of());
        when(settlementJpaRepository.findAllForTreasurer(USER_ID, "PAY")).thenReturn(List.of(projection));

        TreasurerSettlementPersistenceAdapter adapter =
                new TreasurerSettlementPersistenceAdapter(settlementJpaRepository);

        List<TreasurerSettlementRecord> records = adapter.findAll(USER_ID);

        assertThat(records).singleElement().satisfies(record ->
                assertThat(record.allocationType()).isEqualTo(SettlementAllocationType.ITEM_ALLOCATION));
    }
}
