package com.amateur.howmuchbackend.application.settlement.service;

import com.amateur.howmuchbackend.domain.settlement.Settlement;
import com.amateur.howmuchbackend.domain.settlement.SettlementItemSubmission;
import com.amateur.howmuchbackend.domain.settlement.SettlementParticipant;
import com.amateur.howmuchbackend.domain.settlement.SettlementReceivingAccountSnapshot;
import com.amateur.howmuchbackend.domain.settlement.SettlementShare;

import java.util.List;

record SettlementSuspensionContext(
        Settlement settlement,
        SettlementReceivingAccountSnapshot snapshot,
        List<SettlementParticipant> participants,
        List<SettlementShare> shares,
        List<SettlementItemSubmission> submissions
) {
    SettlementSuspensionContext {
        participants = List.copyOf(participants);
        shares = List.copyOf(shares);
        submissions = List.copyOf(submissions);
    }
}
