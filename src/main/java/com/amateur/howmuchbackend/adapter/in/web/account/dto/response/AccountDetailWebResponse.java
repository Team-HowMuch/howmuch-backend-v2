package com.amateur.howmuchbackend.adapter.in.web.account.dto.response;

import com.amateur.howmuchbackend.application.port.in.account.result.AccountDetailResult;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.YearMonth;

public record AccountDetailWebResponse(
        Long id,
        String organization,
        String institutionName,
        String institutionIconUrl,
        String name,
        String maskedAccountNumber,
        boolean isDefault,
        int displayOrder,
        YearMonth yearMonth,
        BigDecimal monthlyExpenseAmount,
        long expenseCount,
        LocalDateTime lastUsedAt,
        @Schema(description = "요청 월의 거래를 한 번이라도 성공적으로 수집했는지 여부. false면 아직 동기화하지 않아 0원으로 보이는 상태다.")
        boolean synced,
        @Schema(nullable = true, description = "요청 월의 마지막 성공 수집 시각. 수집 기록이 없으면 null이며, 과거 월의 부분 수집은 synced=false일 수 있다.")
        LocalDateTime lastSyncedAt,
        @Schema(description = "같은 계좌·월의 동기화 진행 여부. 완료 확인은 이 GET으로 수행한다.")
        boolean syncInProgress
) {
    public static AccountDetailWebResponse from(AccountDetailResult result) {
        return new AccountDetailWebResponse(
                result.id(), result.organization(), result.institutionName(), result.institutionIconUrl(),
                result.name(), result.maskedAccountNumber(), result.isDefault(), result.displayOrder(),
                result.yearMonth(), result.monthlyExpenseAmount(), result.expenseCount(), result.lastUsedAt(),
                result.synced(), result.lastSyncedAt(), result.syncInProgress()
        );
    }
}
