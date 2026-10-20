package com.amateur.howmuchbackend.adapter.out.persistence.settlement.netting;

import com.amateur.howmuchbackend.application.port.out.settlement.netting.*;

import lombok.RequiredArgsConstructor;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
@RequiredArgsConstructor
public class NettingNotificationJdbcAdapter implements LoadNettingNotificationStatePort {
    private final JdbcTemplate jdbc;
    private final LoadGroupNettingPort nettings;
    private final NettingTransferAvailabilityPort availability;

    public boolean visible(UUID id, long user) {
        return Boolean.TRUE.equals(
                jdbc.queryForObject(
                        "SELECT EXISTS(SELECT 1 FROM group_settlement_nettings n JOIN groups g ON"
                            + " g.id=n.group_id JOIN group_members m ON m.group_id=g.id AND"
                            + " m.user_id=? AND m.status='ACTIVE' WHERE n.id=? AND"
                            + " (g.status='ACTIVE' OR EXISTS(SELECT 1 FROM group_netting_parties p"
                            + " WHERE p.netting_id=n.id AND p.user_id=?)))",
                        Boolean.class,
                        user,
                        id,
                        user));
    }

    public Optional<State> find(UUID id, long user, UUID transfer) {
        return nettings.findById(id)
                .map(
                        n -> {
                            var t =
                                    transfer == null
                                            ? null
                                            : n.transfers().stream()
                                                    .filter(
                                                            v ->
                                                                    v.id().equals(transfer)
                                                                            && v.payerId() == user)
                                                    .findFirst()
                                                    .orElse(null);
                            return new State(
                                    visible(id, user),
                                    n.status(),
                                    n.openReviews(),
                                    t == null ? null : t.confirmedAmount(),
                                    t == null ? null : t.remainingAmount(),
                                    availability.isGroupReplacementPending(n.groupId()));
                        });
    }
}
