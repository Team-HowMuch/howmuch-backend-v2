package com.amateur.howmuchbackend.adapter.out.persistence.settlement;

import com.amateur.howmuchbackend.application.port.out.settlement.LoadMySettlementCategoryPort;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.PreparedStatement;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.UUID;

/** 회원별 내 카테고리(V56 settlement_user_categories)와 기본 카테고리(settlement_categories)를 함께 읽는다. */
@Component
@RequiredArgsConstructor
public class SettlementUserCategoryJdbcAdapter implements LoadMySettlementCategoryPort {
    // 정산 수와 관계없이 바인드 값이 두 개가 되도록 ID를 배열 하나로 넘긴다.
    private static final String FIND_CATEGORY_CODES = """
            SELECT settlement.id AS settlement_id,
                   COALESCE(mine.category_code, classified.category_code) AS category_code
            FROM settlements settlement
            LEFT JOIN settlement_user_categories mine
                ON mine.settlement_id = settlement.id AND mine.user_id = ?
            LEFT JOIN settlement_categories classified
                ON classified.settlement_id = settlement.id AND classified.status = 'CLASSIFIED'
            WHERE settlement.id = ANY(?)
              AND (mine.category_code IS NOT NULL OR classified.category_code IS NOT NULL)
            """;

    private final JdbcTemplate jdbc;

    @Override
    public Map<UUID, String> findCategoryCodes(Long userId, Collection<UUID> settlementIds) {
        if (userId == null || settlementIds.isEmpty()) {
            return Map.of();
        }
        Object[] ids = new LinkedHashSet<>(settlementIds).toArray();
        Map<UUID, String> codes = new HashMap<>();
        jdbc.query(connection -> {
            PreparedStatement statement = connection.prepareStatement(FIND_CATEGORY_CODES);
            statement.setLong(1, userId);
            statement.setArray(2, connection.createArrayOf("uuid", ids));
            return statement;
        }, row -> {
            codes.put(row.getObject("settlement_id", UUID.class), row.getString("category_code"));
        });
        return codes;
    }
}
