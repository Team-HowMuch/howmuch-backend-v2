package com.amateur.howmuchbackend.application.port.out.settlement;

import com.amateur.howmuchbackend.domain.settlement.SettlementAllocationType;
import com.amateur.howmuchbackend.domain.settlement.SettlementStatus;

import java.math.BigInteger;
import java.util.UUID;

/** 생성자를 제외한 참여자별 사실이며, 참여자가 없는 정산은 참여자와 Share 값이 null이다. */
public record SettlementUnpaidSummaryReadModel(
        UUID settlementId,
        SettlementStatus status,
        SettlementAllocationType allocationType,
        UUID participantId,
        Long shareAmount,
        BigInteger confirmedAmount
) {
}
