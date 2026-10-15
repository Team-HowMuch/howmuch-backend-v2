package com.amateur.howmuchbackend.domain.settlement;

/** 분류 작업의 마지막 실패 사유. */
public enum SettlementCategoryFailureCode {
    PROVIDER_ERROR,
    INVALID_RESPONSE,
    PROCESSING_TIMEOUT
}
