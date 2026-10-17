package com.amateur.howmuchbackend.adapter.out.persistence.settlement.netting;

import com.amateur.howmuchbackend.application.port.out.settlement.netting.*;
import com.amateur.howmuchbackend.domain.settlement.netting.NettingDebt;

import lombok.RequiredArgsConstructor;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
@RequiredArgsConstructor
public class GroupNettingDebtJdbcAdapter implements LoadGroupNettingDebtPort {
    private final JdbcTemplate jdbc;
    private final com.amateur.howmuchbackend.adapter.out.persistence.common
                    .PostgresLockTimeoutConfigurer
            lockTimeout;
    private final com.amateur.howmuchbackend.infra.codef.config.CodefConfigurationFingerprint
            fingerprint;

    public List<NettingInput.Source> findAwaitingSources(long groupId) {
        return jdbc.query(
                """
SELECT s.id,s.creator_id,s.version,snap.source_account_id,snap.account_number_ciphertext,a.external_connection_id source_connection_id,a.candidate_key,a.external_account_key_ciphertext FROM settlements s LEFT JOIN settlement_receiving_account_snapshots snap ON snap.settlement_id=s.id LEFT JOIN accounts a ON a.id=snap.source_account_id
WHERE s.status='AWAITING_DEPOSIT' AND s.deleted_at IS NULL
  AND EXISTS(SELECT 1 FROM settlement_participants p WHERE p.settlement_id=s.id AND p.source_group_id=?)
  AND NOT EXISTS(SELECT 1 FROM settlement_participants p WHERE p.settlement_id=s.id
    AND NOT ((p.linked_user_id=s.creator_id AND p.source='REGISTERED_USER' AND p.source_group_id IS NULL)
      OR (p.source='GROUP_MEMBER' AND p.source_group_id=?)))
ORDER BY s.id
""",
                (rs, row) -> {
                    UUID id = rs.getObject("id", UUID.class);
                    long creator = rs.getLong("creator_id");
                    var parties =
                            jdbc.query(
                                    """
SELECT id,linked_user_id,source,source_group_id FROM settlement_participants
WHERE settlement_id=? ORDER BY id
""",
                                    (p, n) ->
                                            new NettingInput.Party(
                                                    p.getObject("id", UUID.class),
                                                    p.getObject("linked_user_id", Long.class),
                                                    p.getString("source"),
                                                    p.getObject("source_group_id", Long.class)),
                                    id);
                    var debts =
                            jdbc.query(
                                    """
SELECT sh.id,p.linked_user_id,sh.amount,COALESCE(sum(t.amount),0) confirmed
FROM settlement_shares sh JOIN settlement_participants p ON p.id=sh.payer_participant_id
LEFT JOIN settlement_deposits d ON d.share_id=sh.id
LEFT JOIN account_transactions t ON t.id=d.account_transaction_id
WHERE sh.settlement_id=? AND sh.amount>0 AND p.linked_user_id<>? GROUP BY sh.id,p.linked_user_id,sh.amount ORDER BY sh.id
""",
                                    (d, n) ->
                                            new NettingDebt(
                                                    id,
                                                    d.getObject("id", UUID.class),
                                                    d.getLong("linked_user_id"),
                                                    creator,
                                                    d.getLong("amount"),
                                                    d.getBigDecimal("confirmed").longValueExact()),
                                    id,
                                    creator);
                    var keys =
                            jdbc.query(
                                    """
SELECT t.external_transaction_key FROM settlement_deposits d
JOIN account_transactions t ON t.id=d.account_transaction_id WHERE d.settlement_id=? ORDER BY t.external_transaction_key
""",
                                    (d, n) -> d.getString(1),
                                    id);
                    return new NettingInput.Source(
                            id,
                            rs.getLong("version"),
                            creator,
                            parties,
                            debts,
                            keys,
                            rs.getLong("source_account_id"),
                            digest(
                                    Objects.toString(
                                                    rs.getString("account_number_ciphertext"),
                                                    "MISSING")
                                            + ":"
                                            + rs.getLong("source_connection_id")
                                            + ":"
                                            + Objects.toString(
                                                    rs.getString("candidate_key"), "MISSING")
                                            + ":"
                                            + Objects.toString(
                                                    rs.getString("external_account_key_ciphertext"),
                                                    "MISSING")));
                },
                groupId,
                groupId);
    }

    public long currentVersion(long groupId) {
        return jdbc
                .query(
                        "SELECT version FROM group_netting_states WHERE group_id=?",
                        (rs, n) -> rs.getLong(1),
                        groupId)
                .stream()
                .findFirst()
                .orElse(0L);
    }

    public boolean isReceivingAccountConnected(long accountId) {
        return Boolean.TRUE.equals(
                jdbc.queryForObject(
                        """
SELECT EXISTS(SELECT 1 FROM accounts a JOIN external_connections c ON c.id=a.external_connection_id
  JOIN connected_institutions i ON i.external_connection_id=c.id AND i.organization=a.organization
  WHERE a.id=? AND a.active AND c.active AND c.user_id=a.user_id AND c.configuration_fingerprint=? AND i.status='CONNECTED')
""",
                        Boolean.class,
                        accountId,
                        fingerprint.current()));
    }

    public boolean hasOpenReviews(long groupId) {
        return Boolean.TRUE.equals(
                jdbc.queryForObject(
                        """
SELECT EXISTS(SELECT 1 FROM group_settlement_nettings n WHERE n.group_id=?
 AND EXISTS(SELECT 1 FROM netting_deposit_reviews r WHERE r.netting_id=n.id AND r.status='OPEN'))
""",
                        Boolean.class,
                        groupId));
    }

    public void lockPaymentInputs(LoadGroupNettingPort.Netting netting) {
        lockPartyData(netting.partyIds());
        netting.sources().stream()
                .map(LoadGroupNettingPort.SourceAmount::settlementId)
                .sorted(Comparator.comparing(UUID::toString))
                .forEach(
                        id ->
                                jdbc.queryForList(
                                        "SELECT id FROM settlements WHERE id=? FOR UPDATE", id));
    }

    public void lockInputs(NettingInput input) {
        lockPartyData(input.requiredUserIds());
        var ids = new TreeSet<UUID>(Comparator.comparing(UUID::toString));
        input.sources().forEach(source -> ids.add(source.settlementId()));
        if (input.predecessorNettingId() != null)
            ids.addAll(
                    jdbc.query(
                            "WITH RECURSIVE lineage AS (SELECT id,predecessor_netting_id FROM"
                                + " group_settlement_nettings WHERE id=? UNION ALL SELECT"
                                + " n.id,n.predecessor_netting_id FROM group_settlement_nettings n"
                                + " JOIN lineage l ON n.id=l.predecessor_netting_id) SELECT"
                                + " s.settlement_id FROM group_netting_sources s JOIN lineage l ON"
                                + " l.id=s.netting_id",
                            (rs, row) -> rs.getObject(1, UUID.class),
                            input.predecessorNettingId()));
        ids.forEach(
                id -> jdbc.queryForList("SELECT id FROM settlements WHERE id=? FOR UPDATE", id));
    }

    private String digest(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            java.security.MessageDigest.getInstance("SHA-256")
                                    .digest(
                                            value.getBytes(
                                                    java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private void lockPartyData(Collection<Long> partyIds) {
        lockTimeout.apply();
        // The Application supplies immutable discovered IDs. Never discover new locks after the
        // group lock.
        partyIds.stream()
                .sorted()
                .forEach(id -> jdbc.queryForList("SELECT id FROM users WHERE id=? FOR UPDATE", id));
        var connections = new TreeSet<Long>();
        var accounts = new TreeSet<Long>();
        for (long user : partyIds) {
            connections.addAll(
                    jdbc.query(
                            "SELECT id FROM external_connections WHERE user_id=?",
                            (rs, n) -> rs.getLong(1),
                            user));
            accounts.addAll(
                    jdbc.query(
                            "SELECT id FROM accounts WHERE user_id=?",
                            (rs, n) -> rs.getLong(1),
                            user));
        }
        connections.forEach(
                id ->
                        jdbc.queryForList(
                                "SELECT id FROM external_connections WHERE id=? FOR UPDATE", id));
        accounts.forEach(
                id -> jdbc.queryForList("SELECT id FROM accounts WHERE id=? FOR UPDATE", id));
    }
}
