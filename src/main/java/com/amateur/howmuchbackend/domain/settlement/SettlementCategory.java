package com.amateur.howmuchbackend.domain.settlement;

/**
 * 정산 카테고리 공통 코드.
 *
 * <p>카테고리 목록은 공통 코드 그룹 {@value #GROUP_CODE}에 있다. {@code attr2 = 'Y'}인 코드만 정산에 쓴다.
 */
public final class SettlementCategory {
    public static final String GROUP_CODE = "SETTLEMENT_CATEGORY";
    public static final String UNCATEGORIZED = "UNCATEGORIZED";
    public static final String SETTLEMENT_USE = "Y";

    private SettlementCategory() {
    }
}
