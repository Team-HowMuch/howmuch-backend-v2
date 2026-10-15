package com.amateur.howmuchbackend.adapter.out.persistence.settlement;

import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementCategoryRulePort;
import com.amateur.howmuchbackend.domain.settlement.SettlementCategoryRule;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class SettlementCategoryRuleJdbcAdapter implements LoadSettlementCategoryRulePort {
    private final JdbcTemplate jdbc;

    @Override
    public List<SettlementCategoryRule> findActive() {
        return jdbc.query("""
                SELECT id, keyword, category_code, priority
                FROM settlement_category_rules
                WHERE use_yn = TRUE
                ORDER BY id
                """, (row, index) -> new SettlementCategoryRule(
                row.getLong("id"), row.getString("keyword"), row.getString("category_code"), row.getInt("priority")));
    }
}
