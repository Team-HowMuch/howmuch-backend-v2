package com.amateur.howmuchbackend.application.port.out.settlement;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/** 회원 기준 "내 카테고리" 코드 조회. */
public interface LoadMySettlementCategoryPort {
    /**
     * 정산마다 회원이 고른 카테고리, 없으면 분류가 끝난 기본 카테고리 코드를 돌려준다.
     * 둘 다 없는 정산(분류 전, 배포 전 정산)은 결과에 들어가지 않는다. 한 번의 조회로 읽는다.
     */
    Map<UUID, String> findCategoryCodes(Long userId, Collection<UUID> settlementIds);
}
