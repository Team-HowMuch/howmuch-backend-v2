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

    @Test
    @DisplayName("관리 화면에서 넣은 코드가 저장 형식(대문자로 시작, 대문자·밑줄 50자 이내)이 아니면 정산용이어도 내지 않는다")
    void categories_excludeUnstorableCodes() {
        when(loadCommonCodePort.findActiveByGroupCode("SETTLEMENT_CATEGORY")).thenReturn(List.of(
                code("FOOD", "식비", 1, "food", "Y"),
                code("Food", "소문자", 2, "food", "Y"),
                code("FOOD2", "숫자", 3, "food", "Y"),
                code("CAFE-SNACK", "하이픈", 4, "cafe-snack", "Y"),
                code("CAFE SNACK", "공백", 5, "cafe-snack", "Y"),
                code("_FOOD", "밑줄 시작", 6, "food", "Y"),
                code("A" + "B".repeat(50), "51자", 7, "food", "Y"),
                code("A" + "B".repeat(49), "50자", 8, "food", "Y")));
        SettlementCategoryCatalog catalog = new SettlementCategoryCatalog(loadCommonCodePort);

        assertThat(catalog.byCode().keySet()).containsExactly("FOOD", "A" + "B".repeat(49));
    }

    private static CommonCode code(String code, String name, int order, String icon, String settlementUse) {
        return new CommonCode("SETTLEMENT_CATEGORY", code, name, code + " 예시", order, icon, settlementUse, null, true);
    }
}
