package com.amateur.howmuchbackend.application.port.in.settlement;

import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementPaymentExpenseCandidateResult;

import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

public interface GetSettlementPaymentExpenseCandidatesUseCase {
    List<SettlementPaymentExpenseCandidateResult> get(
            UUID settlementId,
            Long accountId,
            YearMonth yearMonth
    );
}
