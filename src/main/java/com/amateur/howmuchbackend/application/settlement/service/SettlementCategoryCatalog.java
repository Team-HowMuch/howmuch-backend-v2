package com.amateur.howmuchbackend.application.settlement.service;

import com.amateur.howmuchbackend.application.port.in.settlement.GetSettlementCategoriesUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementCategoryResult;
import com.amateur.howmuchbackend.application.port.out.commoncode.LoadCommonCodePort;
import com.amateur.howmuchbackend.domain.commoncode.CommonCode;
import com.amateur.howmuchbackend.domain.settlement.SettlementCategory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 정산 카테고리 목록(STL-008-002). 은행 목록({@code BankCatalog})처럼 공통 코드에서 읽는다.
 * 정산 사용 여부({@code attr2})가 Y인 활성 코드만, {@code sort_order} 순서로 낸다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SettlementCategoryCatalog implements GetSettlementCategoriesUseCase {
    private final LoadCommonCodePort loadCommonCodePort;

    @Override
    public List<SettlementCategoryResult> getCategories() {
        return loadCommonCodePort.findActiveByGroupCode(SettlementCategory.GROUP_CODE).stream()
                .filter(CommonCode::active)
                .filter(code -> SettlementCategory.SETTLEMENT_USE.equals(code.attr2()))
                .sorted(Comparator.comparingInt(CommonCode::sortOrder))
                .map(code -> new SettlementCategoryResult(code.code(), code.name(), code.description(), code.attr1()))
                .toList();
    }

    /** 코드로 찾기 위한 목록. 순서는 {@link #getCategories()}와 같다. */
    public Map<String, SettlementCategoryResult> byCode() {
        Map<String, SettlementCategoryResult> categories = new LinkedHashMap<>();
        getCategories().forEach(category -> categories.put(category.code(), category));
        return categories;
    }
}
