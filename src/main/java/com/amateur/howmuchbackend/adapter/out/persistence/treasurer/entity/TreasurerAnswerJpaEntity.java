package com.amateur.howmuchbackend.adapter.out.persistence.treasurer.entity;

import com.amateur.howmuchbackend.domain.treasurer.InquiryIntent;
import com.amateur.howmuchbackend.domain.treasurer.TreasurerAnswerGeneratedBy;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.springframework.data.domain.Persistable;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * BaseTimeJpaEntity를 상속하지 않는다. V36에 updated_at이 없기 때문이다.
 *
 * <p>답변은 한 번 쓰고 고치지 않으므로 updated_at이 늘 created_at과 같다. created_at은
 * 영속화 시각이 아니라 답변이 사용자에게 나간 시각이어야 하므로 감사 리스너가 아니라 직접 채운다.
 *
 * <p>식별자를 애플리케이션이 만들고 @Version도 없어, Persistable 없이는 save()가 merge로 가서
 * 질문마다 INSERT 앞에 SELECT가 한 번씩 더 나간다. 이 테이블은 덧붙이기만 하므로 항상 새 행이다.
 */
@Entity
@Table(name = "treasurer_answers")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TreasurerAnswerJpaEntity implements Persistable<UUID> {
    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "question", nullable = false, length = 500)
    private String question;

    @Column(name = "answer", nullable = false, length = 2000)
    private String answer;

    @Enumerated(EnumType.STRING)
    @Column(name = "intent", nullable = false, length = 30)
    private InquiryIntent intent;

    @Enumerated(EnumType.STRING)
    @Column(name = "generated_by", nullable = false, length = 20)
    private TreasurerAnswerGeneratedBy generatedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Override
    public boolean isNew() {
        return true;
    }

    public TreasurerAnswerJpaEntity(
            UUID id,
            Long userId,
            String question,
            String answer,
            InquiryIntent intent,
            TreasurerAnswerGeneratedBy generatedBy,
            LocalDateTime createdAt
    ) {
        this.id = id;
        this.userId = userId;
        this.question = question;
        this.answer = answer;
        this.intent = intent;
        this.generatedBy = generatedBy;
        this.createdAt = createdAt;
    }
}
