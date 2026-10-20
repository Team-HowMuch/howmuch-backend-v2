package com.amateur.howmuchbackend.adapter.out.persistence.settlement.netting;

import com.amateur.howmuchbackend.application.port.out.settlement.netting.LoadNettedUnpaidSummaryPort;

import lombok.RequiredArgsConstructor;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class NettedUnpaidSummaryJdbcAdapter implements LoadNettedUnpaidSummaryPort {
    private final JdbcTemplate jdbc;

    public Summary findByReceiver(long receiver) {
        return jdbc.queryForObject(
                """
SELECT COALESCE(sum(remaining),0) amount,count(*) count FROM (
  SELECT t.amount-COALESCE((SELECT sum(tx.amount) FROM netted_transfer_deposits d JOIN account_transactions tx ON tx.id=d.account_transaction_id WHERE d.transfer_id=t.id),0)
    -COALESCE((SELECT sum(tx.amount) FROM netting_deposit_reviews r JOIN account_transactions tx ON tx.id=r.account_transaction_id WHERE r.resolved_transfer_id=t.id AND r.status='APPLIED_TO_NETTING'),0) remaining
  FROM group_netted_transfers t JOIN group_settlement_nettings n ON n.id=t.netting_id
  WHERE t.receiver_id=? AND n.status='ACTIVE') unpaid WHERE remaining>0
""",
                (rs, n) ->
                        new Summary(
                                rs.getBigDecimal("amount").longValueExact(),
                                Math.toIntExact(rs.getLong("count"))),
                receiver);
    }
}
