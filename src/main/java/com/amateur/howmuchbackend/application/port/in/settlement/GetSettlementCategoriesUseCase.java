package com.amateur.howmuchbackend.application.port.in.settlement;

import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementCategoryResult;

import java.util.List;

public interface GetSettlementCategoriesUseCase {
    /** 정산에 쓰는 카테고리(미분류 포함)를 표시 순서대로. */
    List<SettlementCategoryResult> getCategories();
}
