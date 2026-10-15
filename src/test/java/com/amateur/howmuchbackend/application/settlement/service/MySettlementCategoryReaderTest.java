package com.amateur.howmuchbackend.application.settlement.service;

import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementCategoryResult;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadMySettlementCategoryPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("내 카테고리 읽기")
class MySettlementCategoryReaderTest {
    private static final UUID FIRST = UUID.randomUUID();
    private static final UUID SECOND = UUID.randomUUID();
    private static final UUID THIRD = UUID.randomUUID();
    private static final SettlementCategoryResult FOOD = new SettlementCategoryResult("FOOD", "식비", null, "food");

    @Mock private LoadMySettlementCategoryPort port;
    @Mock private SettlementCategoryCatalog catalog;

    @Test
    @DisplayName("코드를 표시명·아이콘으로 바꾸고, 목록에 없는 코드는 빼고, 없는 정산은 결과에 넣지 않는다")
    void read_mapsCodesToCategories() {
        Map<String, SettlementCategoryResult> categories = new LinkedHashMap<>();
        categories.put("FOOD", FOOD);
        when(catalog.byCode()).thenReturn(categories);
        when(port.findCategoryCodes(7L, List.of(FIRST, SECOND, THIRD)))
                .thenReturn(Map.of(FIRST, "FOOD", SECOND, "RETIRED_CODE"));

        var result = new MySettlementCategoryReader(port, catalog).read(7L, List.of(FIRST, SECOND, THIRD));

        assertThat(result).containsExactlyEntriesOf(Map.of(FIRST, FOOD));
    }

    @Test
    @DisplayName("비회원이거나 정산이 없으면 조회하지 않는다")
    void read_skipsWithoutUserOrSettlements() {
        MySettlementCategoryReader reader = new MySettlementCategoryReader(port, catalog);

        assertThat(reader.read(null, List.of(FIRST))).isEmpty();
        assertThat(reader.read(7L, List.of())).isEmpty();
        verifyNoInteractions(port, catalog);
    }

    @Test
    @DisplayName("분류된 정산이 하나도 없으면 카테고리 목록을 읽지 않는다")
    void read_noCodes_skipsCatalog() {
        when(port.findCategoryCodes(7L, List.of(FIRST))).thenReturn(Map.of());

        assertThat(new MySettlementCategoryReader(port, catalog).read(7L, List.of(FIRST))).isEmpty();
        verifyNoInteractions(catalog);
    }
}
