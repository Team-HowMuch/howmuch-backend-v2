package com.amateur.howmuchbackend.adapter.out.persistence.settlement.netting;

import com.amateur.howmuchbackend.application.port.out.settlement.netting.NettingTransferAvailabilityPort;

import lombok.RequiredArgsConstructor;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@RequiredArgsConstructor
public class NettingTransferAvailabilityJdbcAdapter implements NettingTransferAvailabilityPort {
    private final JdbcTemplate jdbc;

    public boolean isGroupReplacementPending(long group) {
        return Boolean.TRUE.equals(
                jdbc.queryForObject(
                        "SELECT EXISTS(SELECT 1 FROM group_netting_executions WHERE group_id=? AND"
                                + " kind='CREATE' AND status IN ('QUEUED','SYNCING','RETRY'))",
                        Boolean.class,
                        group));
    }

    public boolean isOriginalReplacementPending(UUID settlement) {
        return Boolean.TRUE.equals(
                jdbc.queryForObject(
                        "SELECT EXISTS(SELECT 1 FROM settlement_participants p JOIN"
                            + " group_netting_executions e ON e.group_id=p.source_group_id JOIN"
                            + " settlements s ON s.id=p.settlement_id WHERE s.id=? AND"
                            + " s.status='AWAITING_DEPOSIT' AND p.source='GROUP_MEMBER' AND"
                            + " e.kind='CREATE' AND e.status IN ('QUEUED','SYNCING','RETRY') AND"
                            + " NOT EXISTS(SELECT 1 FROM settlement_participants other WHERE"
                            + " other.settlement_id=s.id AND NOT ((other.source='REGISTERED_USER'"
                            + " AND other.linked_user_id=s.creator_id AND other.source_group_id IS"
                            + " NULL) OR (other.source='GROUP_MEMBER' AND"
                            + " other.source_group_id=e.group_id))))",
                        Boolean.class,
                        settlement));
    }
}
