package com.amateur.howmuchbackend.support;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

/** V53가 기록한 은행 입금 사실을 테스트 정리 중에도 보존한다. */
public final class SettlementDepositTestCleanup {
    private static final String IMMUTABLE_DEPOSITS_TRIGGER = "trg_prevent_original_settlement_deposit_rewrite";

    private SettlementDepositTestCleanup() {}

    public static boolean immutableDepositsEnabled(JdbcTemplate jdbc) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                    FROM pg_trigger t
                    JOIN pg_class table_name ON table_name.oid = t.tgrelid
                    WHERE t.tgname = ?
                      AND table_name.relname = 'settlement_deposits'
                      AND NOT t.tgisinternal
                )
                """, Boolean.class, IMMUTABLE_DEPOSITS_TRIGGER));
    }

    public static boolean hasDepositForSettlement(JdbcTemplate jdbc, UUID settlementId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM settlement_deposits WHERE settlement_id = ?)",
                Boolean.class,
                settlementId));
    }

    public static boolean hasDepositForAccount(JdbcTemplate jdbc, Long accountId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM settlement_deposits WHERE source_account_id = ?)",
                Boolean.class,
                accountId));
    }

    public static boolean preserveAccount(JdbcTemplate jdbc, Long accountId) {
        return immutableDepositsEnabled(jdbc) && hasDepositForAccount(jdbc, accountId);
    }

    public static boolean preserveSettlement(JdbcTemplate jdbc, UUID settlementId) {
        return immutableDepositsEnabled(jdbc) && hasDepositForSettlement(jdbc, settlementId);
    }

    public static void deleteDepositsWhenMutable(JdbcTemplate jdbc, UUID settlementId) {
        if (!immutableDepositsEnabled(jdbc)) {
            jdbc.update("DELETE FROM settlement_deposits WHERE settlement_id = ?", settlementId);
        }
    }

    public static void deleteDepositsForAccountWhenMutable(JdbcTemplate jdbc, Long accountId) {
        if (!immutableDepositsEnabled(jdbc)) {
            jdbc.update("DELETE FROM settlement_deposits WHERE source_account_id = ?", accountId);
        }
    }
}
