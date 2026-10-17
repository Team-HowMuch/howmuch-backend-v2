package com.amateur.howmuchbackend.adapter.out.persistence.settlement.netting;

import com.amateur.howmuchbackend.application.port.out.settlement.netting.*;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.account.Transaction;
import com.amateur.howmuchbackend.domain.settlement.netting.NettingStatusCode;

import lombok.RequiredArgsConstructor;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Pattern;

@Component
@RequiredArgsConstructor
public class NettingDepositJdbcAdapter implements NettingDepositStorePort {
    private final JdbcTemplate jdbc;
    private static final Pattern REFERENCE =
            Pattern.compile("(?<![A-Z0-9])H[0-9A-F]{7}(?![A-Z0-9])");

    public Credit credit(LoadGroupNettingPort.Transfer transfer, Transaction t, LocalDateTime now) {
        lockTransaction(t.id());
        if (alreadyCredited(
                transfer.nettingId(), transfer.organization(), t.externalTransactionKey()))
            return Credit.ALREADY_CREDITED;
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO"
                    + " netted_transfer_deposits(id,claim_id,netting_id,receiver_id,transfer_id,source_account_id,account_transaction_id,matched_at)"
                    + " VALUES(?,?,?,?,?,?,?,?)",
                id,
                id,
                transfer.nettingId(),
                transfer.receiverId(),
                transfer.id(),
                t.accountId(),
                t.id(),
                now);
        return Credit.APPLIED;
    }

    public boolean alreadyCredited(UUID netting, String bank, String key) {
        return Boolean.TRUE.equals(
                jdbc.queryForObject(
                        "SELECT EXISTS(SELECT 1 FROM settlement_bank_transaction_claims WHERE"
                                + " organization=? AND external_transaction_key=?)",
                        Boolean.class,
                        bank,
                        key));
    }

    public void openReview(
            UUID requested,
            long receiver,
            String bank,
            Transaction t,
            String ignored,
            LocalDateTime now) {
        lockTransaction(t.id());
        if (alreadyCredited(requested, bank, t.externalTransactionKey())) return;
        var target =
                jdbc.query(
                                "WITH RECURSIVE successors AS (SELECT id,group_id,version FROM"
                                    + " group_settlement_nettings WHERE id=? UNION ALL SELECT"
                                    + " n.id,n.group_id,n.version FROM group_settlement_nettings n"
                                    + " JOIN successors p ON n.predecessor_netting_id=p.id) SELECT"
                                    + " id,group_id FROM successors ORDER BY version DESC LIMIT 1",
                                (rs, n) ->
                                        Map.entry(
                                                rs.getObject("id", UUID.class),
                                                rs.getLong("group_id")),
                                requested)
                        .getFirst();
        UUID id = UUID.randomUUID();
        var refs = new HashSet<String>();
        var matcher =
                REFERENCE.matcher(
                        String.join(
                                        " ",
                                        Objects.toString(t.description(), ""),
                                        Objects.toString(t.description1(), ""),
                                        Objects.toString(t.description2(), ""),
                                        Objects.toString(t.description3(), ""),
                                        Objects.toString(t.description4(), ""))
                                .toUpperCase(Locale.ROOT));
        while (matcher.find()) refs.add(matcher.group());
        List<Map.Entry<UUID, UUID>> source =
                refs.size() != 1
                        ? List.of()
                        : jdbc.query(
                                "WITH RECURSIVE lineage AS (SELECT id,predecessor_netting_id FROM"
                                    + " group_settlement_nettings WHERE id=? UNION ALL SELECT"
                                    + " n.id,n.predecessor_netting_id FROM"
                                    + " group_settlement_nettings n JOIN lineage l ON"
                                    + " n.id=l.predecessor_netting_id) SELECT t.netting_id,t.id"
                                    + " FROM group_netted_transfers t JOIN lineage l ON"
                                    + " l.id=t.netting_id JOIN group_settlement_nettings n ON"
                                    + " n.id=t.netting_id WHERE t.reference=? AND t.receiver_id=?"
                                    + " AND n.status='SUPERSEDED'",
                                (rs, n) ->
                                        Map.entry(
                                                rs.getObject(1, UUID.class),
                                                rs.getObject(2, UUID.class)),
                                target.getKey(),
                                refs.iterator().next(),
                                receiver);
        UUID sourceNetting = source.isEmpty() ? null : source.getFirst().getKey(),
                sourceTransfer = source.isEmpty() ? null : source.getFirst().getValue();
        String reason =
                sourceTransfer != null
                        ? "STALE_REFERENCE"
                        : refs.size() > 1
                                ? "DUPLICATE_REFERENCE"
                                : refs.isEmpty() ? "UNLINKED_INCOME" : "OTHER";
        jdbc.update(
                "INSERT INTO"
                    + " netting_deposit_reviews(id,claim_id,group_id,netting_id,receiver_id,account_transaction_id,source_account_id,source_netting_id,source_transfer_id,reason,status,created_at)"
                    + " VALUES(?,?,?,?,?,?,?,?,?,?,'OPEN',?)",
                id,
                id,
                target.getValue(),
                target.getKey(),
                receiver,
                t.id(),
                t.accountId(),
                sourceNetting,
                sourceTransfer,
                reason,
                now);
    }

    public boolean isReviewed(UUID id, long transaction) {
        return Boolean.TRUE.equals(
                jdbc.queryForObject(
                        "SELECT EXISTS(SELECT 1 FROM netting_deposit_reviews WHERE"
                                + " account_transaction_id=?)",
                        Boolean.class,
                        transaction));
    }

    public List<Review> findReviews(UUID id) {
        return jdbc.query(
                "SELECT * FROM netting_deposit_reviews WHERE netting_id=? ORDER BY created_at,id",
                this::review,
                id);
    }

    public Optional<Review> findReview(UUID id) {
        return jdbc
                .query("SELECT * FROM netting_deposit_reviews WHERE id=?", this::review, id)
                .stream()
                .findFirst();
    }

    public void resolveUnrelatedOrReturned(
            Review r, String decision, long admin, String proof, LocalDateTime now) {
        resolve(r, decision, null, admin, proof, now);
    }

    public void applyReviewed(
            Review r,
            LoadGroupNettingPort.Transfer transfer,
            long admin,
            String proof,
            LocalDateTime now) {
        // Permanent review claims stay immutable. The resolution itself records the reviewed
        // payment.
        resolve(r, "APPLIED_TO_NETTING", transfer.id(), admin, proof, now);
    }

    private void resolve(
            Review r, String decision, UUID transfer, long admin, String proof, LocalDateTime now) {
        if (jdbc.update(
                        "UPDATE netting_deposit_reviews SET"
                            + " status=?,resolved_transfer_id=?,resolved_by=?,resolved_at=?,resolution_note=?"
                            + " WHERE id=? AND status='OPEN'",
                        decision,
                        transfer,
                        admin,
                        now,
                        proof,
                        r.id())
                != 1) throw ApplicationException.of(NettingStatusCode.NETTING_INVALID_REVIEW);
    }

    private void lockTransaction(long id) {
        jdbc.queryForList("SELECT id FROM account_transactions WHERE id=? FOR UPDATE", id);
    }

    private Review review(ResultSet r, int row) throws SQLException {
        var resolved = r.getTimestamp("resolved_at");
        return new Review(
                r.getObject("id", UUID.class),
                r.getObject("netting_id", UUID.class),
                r.getLong("receiver_id"),
                r.getLong("account_transaction_id"),
                r.getLong("source_account_id"),
                r.getString("reason"),
                r.getString("status"),
                null,
                r.getString("resolution_note"),
                r.getObject("resolved_by", Long.class),
                r.getTimestamp("created_at").toLocalDateTime(),
                resolved == null ? null : resolved.toLocalDateTime());
    }
}
