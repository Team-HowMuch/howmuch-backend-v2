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
     * 밀린 것이 없어질 때까지 배치를 이어서 지운다.
     *
     * <p>한 주기에 한 배치만 지우면 답변이 배치 크기보다 빠르게 쌓일 때 밀린 분량이 영영 줄지
     * 않고, 보관 기간이 지난 질문 원문이 계속 남는다. 대신 한 주기에 도는 배치 수를 묶어
     * 정리가 다른 작업을 굶기지 않게 한다. 남으면 다음 주기가 이어서 지운다.
     *
     * @return 이번 주기에 지운 총 건수
     */
    public int purgeAll(Duration retention, int batchSize, int maxBatches) {
        int total = 0;
        for (int batch = 0; batch < maxBatches; batch++) {
            int deleted = purge(retention, batchSize);
            total += deleted;
            if (deleted < batchSize) {
                break;
            }
        }
        return total;
    }

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
