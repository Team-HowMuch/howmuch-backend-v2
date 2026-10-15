package com.amateur.howmuchbackend.domain.settlement;

import java.util.regex.Pattern;

/**
 * 정산 카테고리 공통 코드.
 *
 * <p>카테고리 목록은 공통 코드 그룹 {@value #GROUP_CODE}에 있다. {@code attr2 = 'Y'}인 코드만 정산에 쓴다.
 */
public final class SettlementCategory {
    public static final String GROUP_CODE = "SETTLEMENT_CATEGORY";
    public static final String UNCATEGORIZED = "UNCATEGORIZED";
    public static final String SETTLEMENT_USE = "Y";
    // V56 카테고리 테이블들의 category_code CHECK 제약과 같아야 한다.
    private static final Pattern STORABLE_CODE = Pattern.compile("^[A-Z][A-Z_]{0,49}$");

    private SettlementCategory() {
    }

    /** 공통 코드 관리 화면은 형식을 제한하지 않으므로, 정산 카테고리로 저장할 수 있는 코드인지 따로 본다. */
    public static boolean isStorableCode(String code) {
        return code != null && STORABLE_CODE.matcher(code).matches();
    }
}
