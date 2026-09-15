package com.amateur.howmuchbackend.adapter.out.persistence.treasurer;

import com.amateur.howmuchbackend.adapter.out.persistence.treasurer.mapper.TreasurerAnswerMapper;
import com.amateur.howmuchbackend.adapter.out.persistence.treasurer.repository.TreasurerAnswerJpaRepository;
import com.amateur.howmuchbackend.application.port.out.treasurer.SaveTreasurerAnswerPort;
import com.amateur.howmuchbackend.domain.treasurer.SavedTreasurerAnswer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class TreasurerAnswerPersistenceAdapter implements SaveTreasurerAnswerPort {
    private final TreasurerAnswerJpaRepository answerRepository;

    /**
     * 질문 흐름은 읽기 전용 트랜잭션이므로 새 트랜잭션에서 쓴다.
     *
     * <p>덕분에 저장이 실패해도 질문 흐름이 롤백되지 않고, 답변은 그대로 나간다.
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void save(SavedTreasurerAnswer answer) {
        answerRepository.save(TreasurerAnswerMapper.toEntity(answer));
    }
}
