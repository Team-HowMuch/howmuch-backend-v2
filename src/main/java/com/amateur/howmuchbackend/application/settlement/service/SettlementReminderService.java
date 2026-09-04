package com.amateur.howmuchbackend.application.settlement.service;

import com.amateur.howmuchbackend.application.port.in.settlement.RemindAllSettlementParticipantsUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.RemindSettlementParticipantUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementBulkReminderResult;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementReminderResult;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementParticipantPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementPort;
import com.amateur.howmuchbackend.application.settlement.policy.SettlementReminderPolicy;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.auth.status.AuthStatusCode;
import com.amateur.howmuchbackend.domain.settlement.status.SettlementStatusCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SettlementReminderService implements RemindSettlementParticipantUseCase, RemindAllSettlementParticipantsUseCase {
    private final CurrentActorPort currentActorPort;
    private final LoadSettlementPort settlements;
    private final LoadSettlementParticipantPort participants;
    private final SettlementReminderWriter writer;
    private final SettlementReminderPolicy policy;
    private final SettlementReminderCooldownReader cooldownReader;

    @Override
    public SettlementReminderResult remind(UUID settlementId, UUID participantId) {
        return writer.manual(settlementId, participantId, currentActorPort.getCurrentActor().userId());
    }

    @Override
    public SettlementBulkReminderResult remindAll(UUID settlementId) {
        Long actorId = currentActorPort.getCurrentActor().userId();
        var settlement = settlements.findById(settlementId)
                .orElseThrow(() -> ApplicationException.of(SettlementStatusCode.SETTLEMENT_NOT_FOUND));
        policy.validateManual(settlement, actorId);
        int created = 0;
        List<UUID> candidates = new ArrayList<>();
        for (var participant : participants.findAllBySettlementId(settlementId)) {
            try {
                writer.manual(settlementId, participant.id(), actorId);
                candidates.add(participant.id());
                created++;
            } catch (ApplicationException exception) {
                if (exception.getStatusCode() == SettlementStatusCode.SETTLEMENT_REMINDER_COOLDOWN_ACTIVE) {
                    candidates.add(participant.id());
                } else if (!isSkipped(exception)) {
                    throw exception;
                }
            }
        }
        return new SettlementBulkReminderResult(created,
                cooldownReader.findLatestNextAvailableAt(settlementId, candidates).orElse(null));
    }

    private boolean isSkipped(ApplicationException exception) {
        return exception.getStatusCode() == AuthStatusCode.USER_NOT_FOUND
                || exception.getStatusCode() == SettlementStatusCode.SETTLEMENT_PARTICIPANT_NOT_FOUND
                || exception.getStatusCode() == SettlementStatusCode.SETTLEMENT_REMINDER_TARGET_NOT_LINKED
                || exception.getStatusCode() == SettlementStatusCode.SETTLEMENT_REMINDER_TARGET_NOT_UNPAID;
    }
}
