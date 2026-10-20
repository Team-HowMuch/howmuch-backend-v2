package com.amateur.howmuchbackend.adapter.in.web.account.dto.response;

import com.amateur.howmuchbackend.application.port.in.account.result.AccountTransactionSyncResult;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;

public record AccountTransactionSyncWebResponse(
        Long accountId,
        YearMonth yearMonth,
        @Schema(description = "true면 이번 요청에서 CODEF를 호출해 저장했다. false면 중복 방지 구간 또는 동시 동기화로 저장 결과를 재사용했다.")
        boolean refreshed,
        @Schema(description = "true면 같은 계좌·월 동기화가 이미 진행 중이라 CODEF를 호출하지 않고 저장 결과를 반환했다.")
        boolean syncInProgress,
        @Schema(nullable = true, description = "마지막 성공 수집 시각. 이번에 수집하지 않았어도 기존 성공 시각을 반환하며, 기록이 없으면 null이다.")
        LocalDateTime syncedAt,
        @Schema(nullable = true, description = "CODEF에 요청한 조회 시작일. refreshed=false면 null이다.")
        LocalDate fetchedFrom,
        @Schema(nullable = true, description = "CODEF에 요청한 조회 종료일. refreshed=false면 null이다.")
        LocalDate fetchedTo,
        @Schema(description = "CODEF가 반환한 거래 건수(중복 저장 제거 전).")
        int fetchedCount,
        BigDecimal monthlyExpenseAmount,
        long expenseCount,
        @Schema(nullable = true)
        LocalDateTime lastUsedAt
) {
    public static AccountTransactionSyncWebResponse from(AccountTransactionSyncResult result) {
        return new AccountTransactionSyncWebResponse(
                result.accountId(),
                result.yearMonth(),
                result.refreshed(),
                result.syncInProgress(),
                result.syncedAt(),
                result.fetchedFrom(),
                result.fetchedTo(),
                result.fetchedCount(),
                result.monthlyExpenseAmount(),
                result.expenseCount(),
                result.lastUsedAt()
        );
    }
}
