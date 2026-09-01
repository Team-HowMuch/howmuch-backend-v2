package com.amateur.howmuchbackend.application.settlement.service;

/**
 * 품목 몫 선택에 들어온 경로. 허용 상태와 오류 코드가 경로마다 다르다.
 *
 * <p>{@code LINK}는 요청 이후 상태만 다루고, {@code CREATOR}는 알림 발송 전 {@code DRAFT}에서도
 * 생성자가 자기 몫을 고를 수 있어야 한다.
 */
enum SettlementItemAllocationEntry {
    LINK,
    CREATOR
}
