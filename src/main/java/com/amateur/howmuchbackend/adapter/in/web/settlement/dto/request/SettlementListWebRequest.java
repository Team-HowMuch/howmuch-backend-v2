package com.amateur.howmuchbackend.adapter.in.web.settlement.dto.request;

import com.amateur.howmuchbackend.application.port.in.settlement.command.GetSettlementsCommand;
import com.amateur.howmuchbackend.application.port.in.settlement.command.SettlementListStatusFilter;
import com.amateur.howmuchbackend.application.port.in.settlement.command.SettlementListTab;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Size;

import java.time.YearMonth;

public record SettlementListWebRequest(
        @Schema(description = "조회 탭", example = "RECEIVE", defaultValue = "RECEIVE")
        SettlementListTab tab,
        @Schema(description = "상태 필터", example = "IN_PROGRESS", defaultValue = "IN_PROGRESS")
        SettlementListStatusFilter status,
        @Positive(message = "groupId는 양수여야 합니다.")
        @Schema(description = "그룹 출처 참여자가 있는 정산만 조회", example = "10", nullable = true)
        Long groupId,
        @Pattern(
                regexp = "(?!0000)[0-9]{4}-(0[1-9]|1[0-2])",
                message = "yearMonth는 0001-01부터 9999-12까지의 yyyy-MM 형식이어야 합니다."
        )
        @Schema(
                description = "종료 연월(yyyy-MM, 0001-01~9999-12, Asia/Seoul 기준). 생략하면 전체 기간. 미완료 정산 제외, status와 교집합 적용",
                example = "2026-09", type = "string", implementation = String.class
        )
        String yearMonth,
        @Size(max = 256)
        @Schema(description = "이전 응답의 nextCursor. 첫 조회·필터 변경 시 생략")
        String cursor,
        @Min(1) @Max(50)
        @Schema(description = "페이지 크기 (1~50)", defaultValue = "20", minimum = "1", maximum = "50")
        Integer size
) {
    public GetSettlementsCommand toCommand() {
        return new GetSettlementsCommand(
                tab, status, groupId, yearMonth == null ? null : YearMonth.parse(yearMonth), cursor, size
        );
    }
}
