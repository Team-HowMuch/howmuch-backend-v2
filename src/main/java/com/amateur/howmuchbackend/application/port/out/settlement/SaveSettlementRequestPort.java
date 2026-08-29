package com.amateur.howmuchbackend.application.port.out.settlement;

import com.amateur.howmuchbackend.domain.settlement.SettlementLink;
import com.amateur.howmuchbackend.domain.settlement.SettlementReceivingAccountSnapshot;
import com.amateur.howmuchbackend.domain.settlement.SettlementShare;

import java.util.List;

public interface SaveSettlementRequestPort {
    void save(
            SettlementReceivingAccountSnapshot accountSnapshot,
            List<SettlementShare> shares,
            SettlementLink link,
            String rawLinkToken
    );
}
