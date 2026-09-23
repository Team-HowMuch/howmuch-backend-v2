package com.amateur.howmuchbackend.adapter.out.persistence.commoncode;

import com.amateur.howmuchbackend.adapter.out.persistence.commoncode.entity.CommonCodeJpaEntity;
import com.amateur.howmuchbackend.domain.commoncode.CommonCode;
import com.amateur.howmuchbackend.domain.commoncode.CommonCodeGroup;
import jakarta.persistence.Column;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class CommonCodeAttributeLengthIntegrationTest {
    @Autowired private CommonCodePersistenceAdapter adapter;
    @Autowired private EntityManager entityManager;
    @Autowired private JdbcTemplate jdbc;

    @ParameterizedTest
    @ValueSource(ints = {100, 101, 255})
    void commonCodeAttributes_maxLength_savedWithoutTruncation(int length) {
        String groupCode = "ATTR_" + UUID.randomUUID().toString().replace("-", "");
        var empty = adapter.create(CommonCodeGroup.create(groupCode, "속성 길이", null,
                null, null, null, true));
        String original = "가".repeat(length);
        adapter.update(withDetail(empty, original));
        entityManager.clear();
        var loaded = adapter.findByGroupCode(groupCode).orElseThrow();
        assertAttributes(loaded, original);

        String updated = "나".repeat(length);
        adapter.update(withDetail(loaded, updated));
        entityManager.clear();
        assertAttributes(adapter.findByGroupCode(groupCode).orElseThrow(), updated);
    }

    @Test
    void commonCodeAttributeLengths_jpaAndMigration_match() throws Exception {
        for (String field : List.of("attr1", "attr2", "attr3")) {
            assertThat(CommonCodeJpaEntity.class.getDeclaredField(field)
                    .getAnnotation(Column.class).length()).isEqualTo(255);
            assertThat(jdbc.queryForObject("""
                    select character_maximum_length from information_schema.columns
                    where table_schema = current_schema() and table_name = 'common_code_detail'
                      and column_name = ?
                    """, Integer.class, field)).isEqualTo(255);
        }
    }

    private CommonCodeGroup withDetail(CommonCodeGroup group, String value) {
        return group.update(group.groupName(), group.description(), group.attr1Name(),
                group.attr2Name(), group.attr3Name(), group.active(),
                List.of(new CommonCode(group.groupCode(), "TEST", "상세", null, 0,
                        value, value, value, true)));
    }

    private void assertAttributes(CommonCodeGroup group, String value) {
        assertThat(group.details()).hasSize(1);
        var detail = group.details().getFirst();
        assertThat(List.of(detail.attr1(), detail.attr2(), detail.attr3()))
                .containsExactly(value, value, value);
    }
}
