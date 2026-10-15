package com.amateur.howmuchbackend.application.settlement.service;

import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementCategoryResult;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadMySettlementCategoryPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 정산 목록·상세의 "내 카테고리"를 읽는다. 회원이 고른 값 → 자동 분류 값 순서이고, 둘 다 없으면 결과에 없다(응답 null).
 * 코드가 정산용 카테고리 목록에 없으면(공통 코드에서 내린 경우) 표시할 수 없으므로 결과에서 뺀다.
 */
@Component
@RequiredArgsConstructor
public class MySettlementCategoryReader {
    private final LoadMySettlementCategoryPort loadMySettlementCategoryPort;
    private final SettlementCategoryCatalog catalog;

    public Map<UUID, SettlementCategoryResult> read(Long userId, Collection<UUID> settlementIds) {
        if (userId == null || settlementIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, String> codes = loadMySettlementCategoryPort.findCategoryCodes(userId, settlementIds);
        if (codes.isEmpty()) {
            return Map.of();
        }
        Map<String, SettlementCategoryResult> categories = catalog.byCode();
        Map<UUID, SettlementCategoryResult> result = new HashMap<>();
        codes.forEach((settlementId, code) -> {
            SettlementCategoryResult category = categories.get(code);
            if (category != null) {
                result.put(settlementId, category);
            }
        });
        return result;
    }
}
