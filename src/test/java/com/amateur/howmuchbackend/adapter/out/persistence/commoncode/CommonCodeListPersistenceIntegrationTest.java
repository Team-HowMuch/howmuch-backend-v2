package com.amateur.howmuchbackend.adapter.out.persistence.commoncode;

import com.amateur.howmuchbackend.domain.commoncode.CommonCodeGroupSummary;
import jakarta.persistence.EntityManager;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@Transactional
@DisplayName("공통 코드 그룹 목록 집계 PostgreSQL 회귀 검증")
class CommonCodeListPersistenceIntegrationTest {
    @Autowired private CommonCodePersistenceAdapter adapter;
    @Autowired private EntityManager entityManager;
    @Autowired private JdbcTemplate jdbc;

    private String prefix;
    private Statistics statistics;

    @BeforeEach
    void setUp() {
        prefix = "N312" + UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT);
        statistics = entityManager.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 10, 100})
    @DisplayName("그룹 수가 늘어도 전체·검색 목록은 각각 두 쿼리로 정확한 집계를 반환한다")
    void commonCodeList_groupCountGrows_queryCountStaysConstant(int groupCount) {
        List<CommonCodeGroupSummary> expected = new ArrayList<>();
        for (int index = groupCount - 1; index >= 0; index--) {
            expected.add(insertGroup(prefix + "%03d".formatted(index), "목록 " + index,
                    null, index % 2 == 0, index + 1, index % 3));
        }
        expected.sort(Comparator.comparing(CommonCodeGroupSummary::groupCode));

        var allGroups = findAllWithQueryCount(null, 2);
        assertThat(allGroups).extracting(CommonCodeGroupSummary::groupCode).isSorted();
        assertThat(allGroups.stream().filter(group -> group.groupCode().startsWith(prefix)).toList())
                .containsExactlyElementsOf(expected);

        var searchedGroups = findAllWithQueryCount("  " + prefix.toLowerCase(Locale.ROOT) + "  ", 2);
        assertThat(searchedGroups).containsExactlyElementsOf(expected);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t\n"})
    @DisplayName("검색어가 없거나 공백이면 전체 목록의 빈 그룹·비활성 상세·요약 필드를 유지한다")
    void wholeListPreservesCountsOrderAndSummaryFields(String keyword) {
        var last = insertGroup(prefix + "C", "비활성 그룹", "설명 C", false, 7, 1);
        var first = insertGroup(prefix + "A", "빈 그룹", null, true, 3, 0);
        var middle = insertGroup(prefix + "B", "활성·비활성 상세", "설명 B", true, 5, 2);

        var result = findAllWithQueryCount(keyword, 2);

        assertThat(result).containsSubsequence(first, middle, last);
        assertThat(result).extracting(CommonCodeGroupSummary::groupCode).isSorted();
        assertThat(result).hasSize(jdbc.queryForObject("select count(*) from common_code_group", Integer.class));
    }

    @Test
    @DisplayName("그룹 이름의 대소문자 무시 부분 검색은 매칭 그룹의 개수와 정렬만 반환한다")
    void searchesGroupNamesAndExcludesOtherGroups() {
        String nameKeyword = "Match" + UUID.randomUUID().toString().replace("-", "");
        var last = insertGroup(prefix + "C", "앞 " + nameKeyword, null, false, 9, 2);
        var first = insertGroup(prefix + "A", nameKeyword + " 뒤", "빈 그룹", true, 2, 0);
        insertGroup(prefix + "B", "검색 제외", nameKeyword, true, 4, 3);

        var result = findAllWithQueryCount("  " + nameKeyword.toLowerCase(Locale.ROOT) + "  ", 2);

        assertThat(result).containsExactly(first, last);
    }

    @Test
    @DisplayName("조회한 그룹에 상세가 전혀 없어도 개수 0을 반환한다")
    void returnsZeroWhenEveryMatchingGroupIsEmpty() {
        var expected = insertGroup(prefix + "EMPTY", "빈 그룹", null, true, 1, 0);
        insertGroup(prefix + "OTHER", "검색 제외", null, true, 1, 3);

        assertThat(findAllWithQueryCount(prefix + "EMPTY", 2)).containsExactly(expected);
    }

    @Test
    @DisplayName("검색 결과가 없으면 개수 집계 없이 한 쿼리로 빈 목록을 반환한다")
    void skipsCountQueryWhenNoGroupsMatch() {
        insertGroup(prefix + "OTHER", "검색 제외", null, true, 1, 2);

        assertThat(findAllWithQueryCount(prefix + "MISSING", 1)).isEmpty();
    }

    private List<CommonCodeGroupSummary> findAllWithQueryCount(String keyword, long expectedQueryCount) {
        entityManager.flush();
        entityManager.clear();
        statistics.clear();

        var result = adapter.findAll(keyword);

        assertThat(statistics.getPrepareStatementCount()).isEqualTo(expectedQueryCount);
        return result;
    }

    private CommonCodeGroupSummary insertGroup(
            String groupCode, String groupName, String description, boolean active, long revision, int detailCount
    ) {
        jdbc.update("""
                insert into common_code_group (group_code, group_name, description, use_yn, revision)
                values (?, ?, ?, ?, ?)
                """, groupCode, groupName, description, active, revision);
        for (int index = 0; index < detailCount; index++) {
            jdbc.update("""
                    insert into common_code_detail (group_code, code, name, sort_order, use_yn)
                    values (?, ?, ?, ?, ?)
                    """, groupCode, "CODE" + index, "상세 " + index, index, index % 2 == 0);
        }
        return new CommonCodeGroupSummary(groupCode, groupName, description, active, revision, detailCount);
    }
}
