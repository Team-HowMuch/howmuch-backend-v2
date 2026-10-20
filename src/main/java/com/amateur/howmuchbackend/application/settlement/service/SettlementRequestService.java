package com.amateur.howmuchbackend.application.settlement.service;

import com.amateur.howmuchbackend.application.port.in.settlement.RequestSettlementUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementRequestResult;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 기존 즉시 정산 요청. 실제 기록 처리는 {@link SettlementRequestWriter}가 담당하며 반복 발행과 공유한다.
 * 현재 사용자와 요청 시각을 확인해 업무 트랜잭션을 열고 writer에 위임한다.
 */
@Service
@RequiredArgsConstructor
public class SettlementRequestService implements RequestSettlementUseCase {
    private final CurrentActorPort currentActorPort;
    private final SettlementRequestWriter writer;

    @Override
    @Transactional
    public SettlementRequestResult request(UUID settlementId) {
        return writer.request(settlementId, currentActorPort.getCurrentActor().userId(),
                LocalDateTime.now(ZoneOffset.UTC));
    }
}
