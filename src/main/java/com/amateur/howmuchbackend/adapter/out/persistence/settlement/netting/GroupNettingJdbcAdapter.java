package com.amateur.howmuchbackend.adapter.out.persistence.settlement.netting;

import com.amateur.howmuchbackend.adapter.out.persistence.external.CodefSecretCipher;
import com.amateur.howmuchbackend.application.port.out.settlement.netting.*;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.account.Account;
import com.amateur.howmuchbackend.domain.settlement.netting.NettingStatusCode;

import lombok.RequiredArgsConstructor;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.*;

@Component
@RequiredArgsConstructor
public class GroupNettingJdbcAdapter implements LoadGroupNettingPort, SaveGroupNettingPort {
    private final JdbcTemplate jdbc;
    private final CodefSecretCipher cipher;
    private final tools.jackson.databind.ObjectMapper mapper;

    public Optional<Netting> find(long groupId, UUID id) {
        return jdbc
                .query(
                        """
                        SELECT n.*, e.actor_id AS creator_id
                        FROM group_settlement_nettings n
                        JOIN group_netting_executions e ON e.id=n.execution_id
                        WHERE n.group_id=? AND n.id=?
                        """,
                        (rs, n) ->
                                new Netting(
                                        id,
                                        groupId,
                                        rs.getLong("version"),
                                        rs.getString("status"),
                                        rs.getLong("creator_id"),
                                        rs.getTimestamp("created_at").toLocalDateTime(),
                                        rs.getObject("completed_at", LocalDateTime.class),
                                        rs.getObject("superseded_at", LocalDateTime.class),
                                        sources(id),
                                        transfers(id),
                                        Set.copyOf(
                                                jdbc.query(
                                                        "SELECT user_id FROM group_netting_parties"
                                                                + " WHERE netting_id=?",
                                                        (p, i) -> p.getLong(1),
                                                        id)),
                                        jdbc.queryForObject(
                                                "SELECT count(*) FROM netting_deposit_reviews WHERE"
                                                        + " netting_id=? AND status='OPEN'",
                                                Integer.class,
                                                id)),
                        groupId,
                        id)
                .stream()
                .findFirst();
    }

    public Optional<Netting> findById(UUID id) {
        return jdbc
                .query(
                        "SELECT group_id FROM group_settlement_nettings WHERE id=?",
                        (rs, n) -> rs.getLong(1),
                        id)
                .stream()
                .findFirst()
                .flatMap(group -> find(group, id));
    }

    public Optional<Netting> findBySource(UUID settlement) {
        return jdbc
                .query(
                        "WITH RECURSIVE successors AS (SELECT n.id FROM group_settlement_nettings n"
                            + " JOIN group_netting_sources s ON s.netting_id=n.id WHERE"
                            + " s.settlement_id=? UNION ALL SELECT n.id FROM"
                            + " group_settlement_nettings n JOIN successors p ON"
                            + " n.predecessor_netting_id=p.id) SELECT n.id FROM successors s JOIN"
                            + " group_settlement_nettings n ON n.id=s.id ORDER BY n.version DESC"
                            + " LIMIT 1",
                        (rs, n) -> rs.getObject(1, UUID.class),
                        settlement)
                .stream()
                .findFirst()
                .flatMap(this::findById);
    }

    public List<UUID> findAll(long groupId) {
        return jdbc.query(
                "SELECT id FROM group_settlement_nettings WHERE group_id=? ORDER BY version DESC",
                (rs, n) -> rs.getObject(1, UUID.class),
                groupId);
    }

    private List<SourceAmount> sources(UUID id) {
        return jdbc.query(
                """
                WITH RECURSIVE lineage AS (
                    SELECT id, predecessor_netting_id FROM group_settlement_nettings WHERE id=?
                    UNION ALL
                    SELECT n.id,n.predecessor_netting_id FROM group_settlement_nettings n
                    JOIN lineage l ON n.id=l.predecessor_netting_id
                )
                SELECT source.*, original.title, original.status, original.allocation_type,
                       creator.name AS requester_name,
                       COALESCE(link.created_at,original.created_at) AS requested_at,
                       store.store_name,
                       (SELECT count(*) FROM settlement_participants p
                         WHERE p.settlement_id=source.settlement_id) AS participant_count,
                       payment.payable_count, payment.completed_count,
                       ARRAY(SELECT source.creator_id UNION
                             SELECT sh.payer_user_id FROM group_netting_source_shares sh
                              WHERE sh.netting_id=source.netting_id
                                AND sh.settlement_id=source.settlement_id
                                AND sh.replaced_amount>0) AS debt_party_ids
                  FROM group_netting_sources source
                  JOIN lineage l ON l.id=source.netting_id
                  JOIN settlements original ON original.id=source.settlement_id
                  JOIN users creator ON creator.id=source.creator_id
                  LEFT JOIN settlement_links link ON link.settlement_id=source.settlement_id
                  LEFT JOIN LATERAL (
                      SELECT CASE WHEN count(*) FILTER (WHERE e.source IN ('RECEIPT_OCR','PAYMENT_HISTORY'))=1
                                       AND count(*) FILTER (WHERE e.source='RECEIPT_OCR')=1
                                  THEN max(e.description) FILTER (WHERE e.source='RECEIPT_OCR') END AS store_name
                        FROM settlement_expenses e WHERE e.settlement_id=source.settlement_id
                  ) store ON true
                  LEFT JOIN LATERAL (
                      SELECT count(*) AS payable_count,
                             count(*) FILTER (WHERE original.status='COMPLETED' OR sh.amount <=
                                 COALESCE((SELECT sum(tx.amount) FROM settlement_deposits d
                                     JOIN account_transactions tx ON tx.id=d.account_transaction_id
                                    WHERE d.share_id=sh.id),0)) AS completed_count
                        FROM settlement_shares sh
                        JOIN settlement_participants p ON p.id=sh.payer_participant_id
                       WHERE sh.settlement_id=source.settlement_id
                         AND p.linked_user_id IS DISTINCT FROM source.creator_id
                  ) payment ON true
                 ORDER BY requested_at DESC,source.settlement_id
                """,
                (rs, n) ->
                        new SourceAmount(
                                rs.getObject("settlement_id", UUID.class),
                                rs.getString("title"),
                                rs.getString("status"),
                                rs.getString("requester_name"),
                                rs.getTimestamp("requested_at").toLocalDateTime(),
                                rs.getString("allocation_type"),
                                rs.getLong("original_amount"),
                                rs.getLong("actual_deposit_amount"),
                                rs.getLong("replaced_amount"),
                                rs.getString("store_name"),
                                rs.getInt("participant_count"),
                                rs.getInt("payable_count"),
                                rs.getInt("completed_count"),
                                Set.copyOf(Arrays.asList((Long[]) rs.getArray("debt_party_ids").getArray()))),
                id);
    }

    private List<Transfer> transfers(UUID id) {
        return jdbc.query(
                """
SELECT t.*,r.account_id,m.organization,m.masked_number,t.created_at AS activated_at,
       confirmed.confirmed_amount, confirmed.confirmed_at
FROM group_netted_transfers t
JOIN group_netting_receivers r ON r.netting_id=t.netting_id AND r.user_id=t.receiver_id
JOIN group_netting_monitored_accounts m ON m.netting_id=r.netting_id AND m.user_id=r.user_id AND m.account_id=r.account_id
LEFT JOIN LATERAL (
    SELECT COALESCE(sum(payment.amount),0) AS confirmed_amount, max(payment.confirmed_at) AS confirmed_at
      FROM (
          SELECT tx.amount, d.matched_at AS confirmed_at
            FROM netted_transfer_deposits d JOIN account_transactions tx ON tx.id=d.account_transaction_id
           WHERE d.transfer_id=t.id
          UNION ALL
          SELECT tx.amount, review.resolved_at AS confirmed_at
            FROM netting_deposit_reviews review JOIN account_transactions tx ON tx.id=review.account_transaction_id
           WHERE review.status='APPLIED_TO_NETTING' AND review.resolved_transfer_id=t.id
      ) payment
) confirmed ON true
WHERE t.netting_id=? ORDER BY t.payer_id,t.receiver_id
""",
                (rs, n) ->
                        new Transfer(
                                rs.getObject("id", UUID.class),
                                id,
                                rs.getLong("payer_id"),
                                rs.getLong("receiver_id"),
                                rs.getLong("amount"),
                                rs.getBigDecimal("confirmed_amount").longValueExact(),
                                rs.getString("reference"),
                                rs.getLong("account_id"),
                                rs.getString("organization"),
                                rs.getString("masked_number"),
                                rs.getTimestamp("activated_at").toLocalDateTime(),
                                rs.getObject("confirmed_at", LocalDateTime.class)),
                id);
    }

    public List<ReceivingAccount> monitoredAccounts(UUID id, long user) {
        return jdbc.query(
                "SELECT * FROM group_netting_monitored_accounts WHERE netting_id=? AND user_id=?"
                        + " ORDER BY account_id",
                (rs, n) ->
                        new ReceivingAccount(
                                user,
                                rs.getLong("account_id"),
                                rs.getLong("connection_id"),
                                rs.getString("organization"),
                                cipher.decryptNettingMonitoredAccount(
                                        id,
                                        user,
                                        rs.getLong("account_id"),
                                        rs.getString("account_number_ciphertext")),
                                rs.getTimestamp("activated_at").toLocalDateTime()),
                id,
                user);
    }

    public List<OriginalPaymentCandidate> originalPaymentCandidates(
            UUID id, long receiver, long account) {
        return jdbc.query(
                """
WITH RECURSIVE lineage AS (SELECT id,predecessor_netting_id FROM group_settlement_nettings WHERE id=? UNION ALL SELECT n.id,n.predecessor_netting_id FROM group_settlement_nettings n JOIN lineage l ON n.id=l.predecessor_netting_id) SELECT sh.id,p.display_name,sh.amount-COALESCE((SELECT sum(t.amount) FROM settlement_deposits d
 JOIN account_transactions t ON t.id=d.account_transaction_id WHERE d.share_id=sh.id),0) remaining,s.created_at
FROM group_netting_sources ns JOIN settlements s ON s.id=ns.settlement_id
JOIN settlement_receiving_account_snapshots snapshot ON snapshot.settlement_id=s.id
JOIN settlement_shares sh ON sh.settlement_id=s.id JOIN settlement_participants p ON p.id=sh.payer_participant_id
WHERE ns.netting_id IN(SELECT id FROM lineage) AND s.creator_id=? AND snapshot.source_account_id=?
  AND p.linked_user_id<>s.creator_id ORDER BY sh.id
""",
                (rs, n) ->
                        new OriginalPaymentCandidate(
                                rs.getObject("id", UUID.class),
                                rs.getString("display_name"),
                                rs.getBigDecimal("remaining").longValueExact(),
                                rs.getTimestamp("created_at").toLocalDateTime()),
                id,
                receiver,
                account);
    }

    public ReceivingAccount receivingAccount(UUID id, long receiver) {
        return jdbc
                .query(
                        "SELECT m.* FROM group_netting_receivers r JOIN"
                            + " group_netting_monitored_accounts m ON m.netting_id=r.netting_id AND"
                            + " m.user_id=r.user_id AND m.account_id=r.account_id WHERE"
                            + " r.netting_id=? AND r.user_id=?",
                        (rs, n) ->
                                new ReceivingAccount(
                                        receiver,
                                        rs.getLong("account_id"),
                                        rs.getLong("connection_id"),
                                        rs.getString("organization"),
                                        cipher.decryptNettingMonitoredAccount(
                                                id,
                                                receiver,
                                                rs.getLong("account_id"),
                                                rs.getString("account_number_ciphertext")),
                                        rs.getTimestamp("activated_at").toLocalDateTime()),
                        id,
                        receiver)
                .stream()
                .findFirst()
                .orElseThrow(() -> ApplicationException.of(NettingStatusCode.NETTING_NOT_FOUND));
    }

    public void create(
            com.amateur.howmuchbackend.domain.settlement.netting.GroupSettlementNetting netting,
            UUID executionId,
            NettingInput input,
            List<Account> accounts,
            LocalDateTime now) {
        UUID id = netting.id();
        long group = input.groupId();
        var monitorPlans = new TreeMap<Long, MonitoringPlan>();
        if (input.predecessorNettingId() != null) {
            int changed =
                    jdbc.update(
                            "UPDATE group_settlement_nettings SET"
                                + " status='SUPERSEDED',superseded_at=? WHERE id=? AND group_id=?"
                                + " AND status='ACTIVE' AND NOT EXISTS(SELECT 1 FROM"
                                + " netting_deposit_reviews WHERE netting_id=? AND status='OPEN')",
                            now,
                            input.predecessorNettingId(),
                            group,
                            input.predecessorNettingId());
            if (changed != 1)
                throw ApplicationException.of(NettingStatusCode.NETTING_INPUT_CHANGED);
        }
        jdbc.update(
                "INSERT INTO"
                    + " group_netting_execution_snapshots(execution_id,group_id,owner_id,version,input_fingerprint,input_json,captured_at)"
                    + " VALUES(?,?,?,?,?,?::jsonb,?)",
                executionId,
                group,
                input.ownerId(),
                input.version(),
                com.amateur.howmuchbackend.application.settlement.netting.service
                        .GroupNettingInputReader.fingerprint(input),
                mapper.writeValueAsString(input),
                now);
        jdbc.update(
                "INSERT INTO"
                    + " group_settlement_nettings(id,group_id,version,predecessor_netting_id,execution_id,status,created_at)"
                    + " VALUES(?,?,?,?,?,'BUILDING',?)",
                id,
                group,
                input.version(),
                input.predecessorNettingId(),
                executionId,
                now);
        jdbc.update(
                "INSERT INTO group_netting_states(group_id,version) VALUES(?,?) ON"
                        + " CONFLICT(group_id) DO UPDATE SET version=EXCLUDED.version",
                group,
                input.version());
        input.requiredUserIds()
                .forEach(
                        user ->
                                jdbc.update(
                                        "INSERT INTO"
                                            + " group_netting_parties(netting_id,group_id,user_id)"
                                            + " VALUES(?,?,?)",
                                        id,
                                        group,
                                        user));
        for (var source : input.sources()) {
            long original = 0, paid = 0;
            for (var debt : source.debts()) {
                original = Math.addExact(original, debt.amount());
                paid = Math.addExact(paid, debt.confirmedAmount());
            }
            jdbc.update(
                    "INSERT INTO"
                        + " group_netting_sources(netting_id,group_id,settlement_id,creator_id,original_amount,actual_deposit_amount,replaced_amount,captured_at)"
                        + " VALUES(?,?,?,?,?,?,?,?)",
                    id,
                    group,
                    source.settlementId(),
                    source.creatorId(),
                    original,
                    paid,
                    Math.subtractExact(original, paid),
                    now);
            for (var debt : source.debts()) {
                var party =
                        source.parties().stream()
                                .filter(v -> Objects.equals(v.userId(), debt.payerId()))
                                .findFirst()
                                .orElseThrow();
                jdbc.update(
                        "INSERT INTO"
                            + " group_netting_source_shares(netting_id,group_id,settlement_id,share_id,payer_participant_id,payer_user_id,original_amount,actual_deposit_amount,replaced_amount)"
                            + " VALUES(?,?,?,?,?,?,?,?,?)",
                        id,
                        group,
                        source.settlementId(),
                        debt.shareId(),
                        party.participantId(),
                        debt.payerId(),
                        debt.amount(),
                        debt.confirmedAmount(),
                        debt.remainingAmount());
            }
            jdbc.query(
                    "SELECT"
                        + " s.source_account_id,s.organization,s.account_number_ciphertext,a.external_connection_id,a.masked_number,s.created_at"
                        + " FROM settlement_receiving_account_snapshots s JOIN accounts a ON"
                        + " a.id=s.source_account_id WHERE s.settlement_id=?",
                    (rs, n) -> {
                        planMonitor(
                                monitorPlans,
                                source.creatorId(),
                                rs.getLong("source_account_id"),
                                rs.getLong("external_connection_id"),
                                rs.getString("organization"),
                                cipher.decryptSettlementAccountNumber(
                                        source.settlementId(),
                                        rs.getString("account_number_ciphertext")),
                                rs.getString("masked_number"),
                                rs.getTimestamp("created_at").toLocalDateTime());
                        return true;
                    },
                    source.settlementId());
        }
        if (input.predecessorNettingId() != null) {
            UUID previous = input.predecessorNettingId();
            jdbc.query(
                    "SELECT m.*,a.external_connection_id current_connection FROM"
                        + " group_netting_monitored_accounts m JOIN accounts a ON a.id=m.account_id"
                        + " WHERE m.netting_id=?",
                    (rs, n) -> {
                        long user = rs.getLong("user_id"), account = rs.getLong("account_id");
                        planMonitor(
                                monitorPlans,
                                user,
                                account,
                                rs.getLong("current_connection"),
                                rs.getString("organization"),
                                cipher.decryptNettingMonitoredAccount(
                                        previous,
                                        user,
                                        account,
                                        rs.getString("account_number_ciphertext")),
                                rs.getString("masked_number"),
                                rs.getTimestamp("activated_at").toLocalDateTime());
                        return true;
                    },
                    previous);
            jdbc.update(
                    "INSERT INTO"
                        + " group_netting_rollovers(successor_netting_id,predecessor_netting_id,group_id,created_at)"
                        + " VALUES(?,?,?,?)",
                    id,
                    previous,
                    group,
                    now);
            for (var transfer : input.carriedTransfers()) {
                jdbc.update(
                        "INSERT INTO"
                            + " group_netting_rollover_transfers(successor_netting_id,predecessor_netting_id,previous_transfer_id,payer_id,receiver_id,original_amount,confirmed_amount,carried_amount,captured_at)"
                            + " VALUES(?,?,?,?,?,?,?,?,?)",
                        id,
                        previous,
                        transfer.id(),
                        transfer.payerId(),
                        transfer.receiverId(),
                        transfer.amount(),
                        transfer.confirmedAmount(),
                        transfer.remainingAmount(),
                        now);
                if (transfer.remainingAmount() > 0)
                    jdbc.update(
                            "INSERT INTO"
                                + " group_netting_rollover_obligations(successor_netting_id,previous_transfer_id,payer_id,receiver_id,carried_amount)"
                                + " VALUES(?,?,?,?,?)",
                            id,
                            transfer.id(),
                            transfer.payerId(),
                            transfer.receiverId(),
                            transfer.remainingAmount());
            }
        }
        for (var account : accounts)
            planMonitor(
                    monitorPlans,
                    account.userId(),
                    account.id(),
                    account.externalConnectionId(),
                    account.organization(),
                    account.externalAccountKey(),
                    account.maskedNumber(),
                    now);
        for (var plan : monitorPlans.values())
            monitor(
                    id,
                    group,
                    plan.user(),
                    plan.account(),
                    plan.connection(),
                    plan.bank(),
                    plan.number(),
                    plan.masked(),
                    plan.activated());
        for (var account : accounts) {
            jdbc.update(
                    "INSERT INTO group_netting_receivers(netting_id,group_id,user_id,account_id)"
                            + " VALUES(?,?,?,?)",
                    id,
                    group,
                    account.userId(),
                    account.id());
        }
        for (var payment : input.calculation().payments()) {
            boolean inserted = false;
            for (int attempt = 0; attempt < 8 && !inserted; attempt++) {
                UUID transfer = UUID.randomUUID();
                String reference =
                        "H"
                                + transfer.toString()
                                        .replace("-", "")
                                        .substring(0, 7)
                                        .toUpperCase(Locale.ROOT);
                inserted =
                        jdbc.update(
                                        "INSERT INTO"
                                            + " group_netted_transfers(id,netting_id,group_id,payer_id,receiver_id,amount,reference,created_at)"
                                            + " VALUES(?,?,?,?,?,?,?,?) ON CONFLICT(reference) DO"
                                            + " NOTHING",
                                        transfer,
                                        id,
                                        group,
                                        payment.payerId(),
                                        payment.receiverId(),
                                        payment.amount(),
                                        reference,
                                        now)
                                == 1;
            }
            if (!inserted) throw new IllegalStateException("NETTING_REFERENCE_EXHAUSTED");
        }
        jdbc.update("UPDATE group_settlement_nettings SET status='ACTIVE' WHERE id=?", id);
    }

    private record MonitoringPlan(
            long user,
            long account,
            long connection,
            String bank,
            String number,
            String masked,
            LocalDateTime activated) {
        @Override
        public String toString() {
            return "MonitoringPlan[redacted]";
        }
    }

    private void planMonitor(
            Map<Long, MonitoringPlan> plans,
            long user,
            long account,
            long connection,
            String bank,
            String number,
            String masked,
            LocalDateTime activated) {
        var plan = new MonitoringPlan(user, account, connection, bank, number, masked, activated);
        plans.merge(
                account,
                plan,
                (old, current) -> old.activated().isBefore(current.activated()) ? old : current);
    }

    private void monitor(
            UUID id,
            long group,
            long user,
            long account,
            long connection,
            String bank,
            String number,
            String masked,
            LocalDateTime activated) {
        jdbc.update(
                "INSERT INTO"
                    + " group_netting_monitored_accounts(netting_id,group_id,user_id,account_id,connection_id,organization,account_number_ciphertext,masked_number,activated_at)"
                    + " VALUES(?,?,?,?,?,?,?,?,?) ON CONFLICT(netting_id,user_id,account_id) DO"
                    + " NOTHING",
                id,
                group,
                user,
                account,
                connection,
                bank,
                cipher.encryptNettingMonitoredAccount(id, user, account, number),
                masked,
                activated);
    }

    public Optional<Netting> findActive(long group) {
        return jdbc
                .query(
                        "SELECT id FROM group_settlement_nettings WHERE group_id=? AND"
                                + " status='ACTIVE'",
                        (rs, n) -> rs.getObject(1, UUID.class),
                        group)
                .stream()
                .findFirst()
                .flatMap(this::findById);
    }

    public List<ReceivingAccount> monitoredAccounts(UUID id) {
        return jdbc
                .query(
                        "SELECT DISTINCT user_id FROM group_netting_monitored_accounts WHERE"
                                + " netting_id=? ORDER BY user_id",
                        (rs, n) -> rs.getLong(1),
                        id)
                .stream()
                .flatMap(user -> monitoredAccounts(id, user).stream())
                .toList();
    }

    public void lockNetting(UUID id) {
        jdbc.queryForList("SELECT id FROM group_settlement_nettings WHERE id=? FOR UPDATE", id);
    }

    public boolean completeIfPaid(UUID id, LocalDateTime now) {
        lockNetting(id);
        return jdbc.update(
                        """
UPDATE group_settlement_nettings n SET status='COMPLETED',completed_at=? WHERE n.id=? AND n.status='ACTIVE'
 AND NOT EXISTS(SELECT 1 FROM netting_deposit_reviews r WHERE r.netting_id=n.id AND r.status='OPEN')
 AND NOT EXISTS(SELECT 1 FROM group_netted_transfers t WHERE t.netting_id=n.id AND t.amount<>
   COALESCE((SELECT sum(tx.amount) FROM netted_transfer_deposits d JOIN account_transactions tx ON tx.id=d.account_transaction_id
     WHERE d.transfer_id=t.id),0)+COALESCE((SELECT sum(tx.amount) FROM netting_deposit_reviews review JOIN account_transactions tx ON tx.id=review.account_transaction_id WHERE review.status='APPLIED_TO_NETTING' AND review.resolved_transfer_id=t.id),0))
""",
                        now,
                        id)
                == 1;
    }
}
