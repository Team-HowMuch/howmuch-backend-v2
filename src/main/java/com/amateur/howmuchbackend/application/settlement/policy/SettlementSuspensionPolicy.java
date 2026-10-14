package com.amateur.howmuchbackend.application.settlement.policy;

import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.settlement.Settlement;
import com.amateur.howmuchbackend.domain.settlement.SettlementItemSubmission;
import com.amateur.howmuchbackend.domain.settlement.SettlementParticipant;
import com.amateur.howmuchbackend.domain.settlement.SettlementShare;
import com.amateur.howmuchbackend.domain.settlement.SettlementStatus;
import com.amateur.howmuchbackend.domain.settlement.status.SettlementStatusCode;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class SettlementSuspensionPolicy {
    public boolean requiresDepositRefresh(
            Settlement settlement,
            List<SettlementShare> shares,
            List<SettlementParticipant> participants,
            List<SettlementItemSubmission> submissions
    ) {
        if (settlement.status() == SettlementStatus.AWAITING_DEPOSIT) {
            return true;
        }
        if (settlement.status() != SettlementStatus.REQUESTED) {
            return false;
        }

        Map<UUID, SettlementParticipant> participantById = participants.stream()
                .collect(Collectors.toMap(SettlementParticipant::id, Function.identity()));
        boolean hasTransferCapableShare = shares.stream().anyMatch(share -> {
            SettlementParticipant participant = participantById.get(share.payerParticipantId());
            return participant == null
                    || !settlement.creatorId().equals(participant.linkedUserId());
        });
        boolean hasOtherParticipantSubmission = submissions.stream().anyMatch(submission -> {
            SettlementParticipant participant = participantById.get(submission.participantId());
            return participant == null
                    || !settlement.creatorId().equals(participant.linkedUserId());
        });
        return hasTransferCapableShare || hasOtherParticipantSubmission;
    }

    public void validateNoPayments(
            boolean hasRecordedDeposit,
            boolean hasUnmatchedPositiveIncome
    ) {
        if (hasRecordedDeposit || hasUnmatchedPositiveIncome) {
            throw ApplicationException.of(SettlementStatusCode.SETTLEMENT_NOT_SUSPENDABLE);
        }
    }
}
