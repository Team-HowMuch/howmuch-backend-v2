package com.amateur.howmuchbackend.adapter.out.persistence.treasurer.repository;

import com.amateur.howmuchbackend.adapter.out.persistence.treasurer.entity.TreasurerAnswerReportJpaEntity;
import com.amateur.howmuchbackend.domain.treasurer.TreasurerAnswerReport;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

public interface TreasurerAnswerReportJpaRepository
        extends JpaRepository<TreasurerAnswerReportJpaEntity, UUID> {
    /**
     * 같은 답변에 대한 신고가 이미 있으면 아무것도 하지 않는다.
     *
     * <p>먼저 조회하고 저장하면 동시 요청 사이에 창이 생긴다. 유니크 제약에 직접 맡긴다.
     * 신고자가 그 답변의 소유자가 아니면 복합 FK가 거부하므로 0건이 아니라 예외가 난다.
     * 호출 전에 소유를 확인하므로 정상 흐름에서는 도달하지 않는다.
     */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            INSERT INTO treasurer_answer_reports
                (id, answer_id, reporter_user_id, reason, detail, status, created_at, resolved_at)
            VALUES (:#{#r.id()}, :#{#r.answerId()}, :#{#r.reporterUserId()}, :#{#r.reason().name()},
                :#{#r.detail()}, :#{#r.status().name()}, :#{#r.createdAt()}, :#{#r.resolvedAt()})
            ON CONFLICT (answer_id) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("r") TreasurerAnswerReport report);
}
