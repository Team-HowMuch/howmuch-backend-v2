package com.amateur.howmuchbackend.domain.settlement;

/** 분류 규칙: 가게 이름·거래명·품목명에 키워드가 있으면 카테고리로 분류한다. */
public record SettlementCategoryRule(
        long id,
        String keyword,
        String categoryCode,
        int priority
) {
}
