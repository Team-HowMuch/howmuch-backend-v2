package com.amateur.howmuchbackend.application.port.out.settlement;

import java.time.LocalDateTime;
import java.util.UUID;

/** 회원 기준 "내 카테고리" 저장. */
public interface SaveMySettlementCategoryPort {
    /** (정산, 회원)마다 하나만 둔다. 이미 고른 값이 있으면 새 코드로 덮어쓴다. */
    void save(UUID settlementId, Long userId, String categoryCode, LocalDateTime now);
}
