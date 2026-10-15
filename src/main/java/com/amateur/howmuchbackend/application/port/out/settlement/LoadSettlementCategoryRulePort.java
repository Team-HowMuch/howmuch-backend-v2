package com.amateur.howmuchbackend.application.port.out.settlement;

import com.amateur.howmuchbackend.domain.settlement.SettlementCategoryRule;

import java.util.List;

public interface LoadSettlementCategoryRulePort {
    List<SettlementCategoryRule> findActive();
}
