package com.amateur.howmuchbackend.application.port.in.settlement;

import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementTransferIntentResult;
import java.util.UUID;

public interface RecordMySettlementTransferIntentUseCase {
    SettlementTransferIntentResult recordMyIntent(UUID settlementId);
}
