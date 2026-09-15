package com.amateur.howmuchbackend.application.treasurer.service;

import com.amateur.howmuchbackend.application.port.out.treasurer.DeleteExpiredTreasurerAnswerPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * 보관 기간이 지난 답변을 정리한다.
 *
 * <p>질문 원문에 모임 이름이나 금액 같은 개인적인 내용이 들어갈 수 있어 오래 두지 않는다.
 * 신고가 달린 답변은 남긴다. 신고를 확인하려면 무엇을 묻고 무엇을 답했는지 봐야 한다.
 */
@Service
@RequiredArgsConstructor
public class PurgeExpiredTreasurerAnswerService {
    private final DeleteExpiredTreasurerAnswerPort deleteExpiredTreasurerAnswerPort;
    private final Clock clock;

    /**
     * 한 번에 배치 크기만큼만 지운다.
     *
     * @return 지운 건수. 배치 크기와 같으면 아직 남아 있을 수 있다.
     */
    public int purge(Duration retention, int batchSize) {
        LocalDateTime createdBefore = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
                .minus(retention);
        return deleteExpiredTreasurerAnswerPort.deleteExpired(createdBefore, batchSize);
    }
}
