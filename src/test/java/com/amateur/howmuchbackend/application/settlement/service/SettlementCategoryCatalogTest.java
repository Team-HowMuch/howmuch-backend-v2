package com.amateur.howmuchbackend.application.settlement.service;

import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementCategoryResult;
import com.amateur.howmuchbackend.application.port.out.commoncode.LoadCommonCodePort;
import com.amateur.howmuchbackend.domain.commoncode.CommonCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("정산 카테고리 목록")
class SettlementCategoryCatalogTest {
    @Mock private LoadCommonCodePort loadCommonCodePort;

    @Test
    @DisplayName("정산에 쓰는 코드만 표시 순서대로, 미분류를 포함해 낸다")
    void categories_settlementUseInOrder() {
        when(loadCommonCodePort.findActiveByGroupCode("SETTLEMENT_CATEGORY")).thenReturn(List.of(
                code("UNCATEGORIZED", "미분류", 18, "uncategorized", "Y"),
                code("TRANSFER", "이체", 19, "transfer", "N"),
                code("CAFE_SNACK", "카페·간식", 2, "cafe-snack", "Y"),
                code("FOOD", "식비", 1, "food", "Y")));
        SettlementCategoryCatalog catalog = new SettlementCategoryCatalog(loadCommonCodePort);

        assertThat(catalog.getCategories()).containsExactly(
                new SettlementCategoryResult("FOOD", "식비", "FOOD 예시", "food"),
                new SettlementCategoryResult("CAFE_SNACK", "카페·간식", "CAFE_SNACK 예시", "cafe-snack"),
                new SettlementCategoryResult("UNCATEGORIZED", "미분류", "UNCATEGORIZED 예시", "uncategorized"));
        assertThat(catalog.byCode().keySet()).containsExactly("FOOD", "CAFE_SNACK", "UNCATEGORIZED");
    }

    private static CommonCode code(String code, String name, int order, String icon, String settlementUse) {
        return new CommonCode("SETTLEMENT_CATEGORY", code, name, code + " 예시", order, icon, settlementUse, null, true);
    }
}
