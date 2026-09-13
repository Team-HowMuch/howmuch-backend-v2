package com.amateur.howmuchbackend.adapter.in.web.account.dto.response;

import com.amateur.howmuchbackend.application.port.in.account.result.AccountTransactionResult;
import com.amateur.howmuchbackend.domain.account.AccountTransactionType;
import com.amateur.howmuchbackend.domain.settlement.SettlementStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record AccountTransactionWebResponse(
        Long id,
        AccountTransactionType type,
        BigDecimal amount,
        LocalDateTime transactedAt,
        String description,
        BigDecimal afterBalance,
        List<SettlementSummaryWebResponse> settlements,
        DisplayWebResponse display
) {
    public static AccountTransactionWebResponse from(AccountTransactionResult result) {
        return new AccountTransactionWebResponse(
                result.id(), result.type(), result.amount(), result.transactedAt(), result.description(),
                result.afterBalance(), result.settlements().stream()
                        .map(SettlementSummaryWebResponse::from)
                        .toList(),
                DisplayWebResponse.from(result.display())
        );
    }

    public record SettlementSummaryWebResponse(
            UUID settlementId,
            String title,
            SettlementStatus status
    ) {
        private static SettlementSummaryWebResponse from(
                AccountTransactionResult.SettlementSummary result
        ) {
            return new SettlementSummaryWebResponse(
                    result.settlementId(),
                    result.title(),
                    result.status()
            );
        }
    }

    public record DisplayWebResponse(String title, Kind kind) {
        private static DisplayWebResponse from(AccountTransactionResult.Display result) {
            if (result == null || result.kind() == null) {
                return result == null ? null : new DisplayWebResponse(result.title(), null);
            }
            return new DisplayWebResponse(result.title(), Kind.valueOf(result.kind().name()));
        }

        public enum Kind {
            TRANSFER,
            CARD,
            ATM,
            FEE,
            OTHER
        }
    }
}
