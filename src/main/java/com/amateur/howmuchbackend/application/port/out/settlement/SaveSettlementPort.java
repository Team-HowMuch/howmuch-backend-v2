package com.amateur.howmuchbackend.application.port.out.settlement;

import com.amateur.howmuchbackend.domain.settlement.Settlement;

public interface SaveSettlementPort {
    Settlement save(Settlement settlement);
}
