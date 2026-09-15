package com.amateur.howmuchbackend.adapter.out.persistence.treasurer.repository;

import com.amateur.howmuchbackend.adapter.out.persistence.treasurer.entity.TreasurerAnswerJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

public interface TreasurerAnswerJpaRepository extends JpaRepository<TreasurerAnswerJpaEntity, UUID> {
    Optional<TreasurerAnswerJpaEntity> findByIdAndUserId(UUID id, Long userId);

    /**
     * 신고가 달리지 않은 만료 답변만 지운다.
     *
     * <p>신고된 답변은 FK가 RESTRICT라 지우려 하면 예외가 난다. 조건으로도 걸러 한 건 때문에
     * 배치 전체가 실패하지 않게 한다. 한 번에 지우는 양은 배치 크기로 제한해 긴 잠금을 피한다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            DELETE FROM treasurer_answers
            WHERE id IN (
                SELECT a.id FROM treasurer_answers a
                WHERE a.created_at < :createdBefore
                  AND NOT EXISTS (
                      SELECT 1 FROM treasurer_answer_reports r WHERE r.answer_id = a.id
                  )
                ORDER BY a.created_at
                LIMIT :batchSize
            )
            """, nativeQuery = true)
    int deleteExpired(
            @Param("createdBefore") LocalDateTime createdBefore,
            @Param("batchSize") int batchSize
    );
}
