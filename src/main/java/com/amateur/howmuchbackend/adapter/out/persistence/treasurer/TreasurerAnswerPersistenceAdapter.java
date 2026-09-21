package com.amateur.howmuchbackend.adapter.out.persistence.treasurer;

import com.amateur.howmuchbackend.adapter.out.persistence.treasurer.mapper.TreasurerAnswerMapper;
import com.amateur.howmuchbackend.adapter.out.persistence.treasurer.repository.TreasurerAnswerJpaRepository;
import com.amateur.howmuchbackend.adapter.out.persistence.treasurer.repository.TreasurerAnswerReportJpaRepository;
import com.amateur.howmuchbackend.application.port.out.treasurer.DeleteExpiredTreasurerAnswerPort;
import com.amateur.howmuchbackend.application.port.out.treasurer.LoadTreasurerAnswerPort;
import com.amateur.howmuchbackend.application.port.out.treasurer.SaveTreasurerAnswerPort;
import com.amateur.howmuchbackend.application.port.out.treasurer.SaveTreasurerAnswerReportPort;
import com.amateur.howmuchbackend.domain.treasurer.SavedTreasurerAnswer;
import com.amateur.howmuchbackend.domain.treasurer.TreasurerAnswerReport;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class TreasurerAnswerPersistenceAdapter implements
        SaveTreasurerAnswerPort,
        LoadTreasurerAnswerPort,
        SaveTreasurerAnswerReportPort,
        DeleteExpiredTreasurerAnswerPort {
    private final TreasurerAnswerJpaRepository answerRepository;
    private final TreasurerAnswerReportJpaRepository reportRepository;

    /**
     * 호출자의 트랜잭션에 절대 끼지 않는다.
     *
     * <p>질문 흐름은 트랜잭션을 열지 않으므로 지금은 이 저장이 유일한 트랜잭션이고 커넥션도
     * 하나만 쓴다. REQUIRES_NEW를 남겨 두는 것은, 나중에 누가 이 흐름을 읽기 전용 트랜잭션으로
     * 감싸더라도 저장이 조용히 실패하지 않게 하기 위해서다.
     *
     * <p>저장이 실패해도 질문 흐름이 롤백되지 않고, 답변은 그대로 나간다.
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void save(SavedTreasurerAnswer answer) {
        answerRepository.save(TreasurerAnswerMapper.toEntity(answer));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<SavedTreasurerAnswer> findOwned(UUID answerId, Long userId) {
        return answerRepository.findByIdAndUserId(answerId, userId)
                .map(TreasurerAnswerMapper::toDomain);
    }

    @Override
    @Transactional
    public boolean saveIfAbsent(TreasurerAnswerReport report) {
        return reportRepository.insertIfAbsent(report) == 1;
    }

    @Override
    @Transactional
    public int deleteExpired(LocalDateTime createdBefore, int batchSize) {
        return answerRepository.deleteExpired(createdBefore, batchSize);
    }
}
