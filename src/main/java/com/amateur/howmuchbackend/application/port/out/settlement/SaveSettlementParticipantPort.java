package com.amateur.howmuchbackend.application.port.out.settlement;

import com.amateur.howmuchbackend.domain.settlement.SettlementParticipant;

public interface SaveSettlementParticipantPort {
    SettlementParticipant save(SettlementParticipant participant);
}
