package com.amateur.howmuchbackend.application.port.in.settlement.result;

/**
 * 정산 카테고리 하나. 공통 코드 {@code SETTLEMENT_CATEGORY}의 값이다.
 *
 * @param code 카테고리 코드(예: {@code CAFE_SNACK})
 * @param name 표시명
 * @param description 세부 예시. 없으면 null
 * @param icon 앱이 아이콘 이미지에 연결하는 아이콘 키(예: {@code cafe-snack})
 */
public record SettlementCategoryResult(String code, String name, String description, String icon) {
}
