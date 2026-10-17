package com.amateur.howmuchbackend.adapter.in.web.settlement.docs;

import com.amateur.howmuchbackend.adapter.in.web.common.ApiResponse;
import com.amateur.howmuchbackend.adapter.in.web.settlement.dto.request.CreateRecurringSettlementWebRequest;
import com.amateur.howmuchbackend.adapter.in.web.settlement.dto.response.RecurringSettlementOccurrenceWebResponse;
import com.amateur.howmuchbackend.adapter.in.web.settlement.dto.response.RecurringSettlementPageWebResponse;
import com.amateur.howmuchbackend.adapter.in.web.settlement.dto.response.RecurringSettlementSchedulePreviewWebResponse;
import com.amateur.howmuchbackend.adapter.in.web.settlement.dto.response.RecurringSettlementSummaryWebResponse;
import com.amateur.howmuchbackend.adapter.in.web.settlement.dto.response.RecurringSettlementWebResponse;
import com.amateur.howmuchbackend.application.settlement.model.RecurringSettlementScope;
import com.amateur.howmuchbackend.application.settlement.model.RecurringSettlementStatusFilter;
import com.amateur.howmuchbackend.domain.settlement.recurring.RecurrenceSchedule;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.UUID;

@Tag(name = "그룹 반복 정산", description = "그룹 고정 비용의 반복 등록·조회·해지")
@SecurityRequirement(name = "bearerAuth")
@ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "처리 성공",
            useReturnTypeSchema = true),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "일정·카테고리·초안·조회 조건 오류"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 필요"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "접근 권한 없음"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "그룹·초안·반복 설정 없음"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "원본 초안이 다른 조건으로 이미 등록됨 또는 요청 조건 불충족")
})
public interface RecurringSettlementDocs {
    @Operation(summary = "그룹 반복 정산 등록",
            description = "수동 더치페이 초안을 반복 설정으로 전환합니다. 첫 정산일부터 한국 시간 09:00에 요청하며, "
                    + "등록 즉시 청구하지 않습니다. firstRunDate를 생략하면 가장 가까운 미래 유효 일정을 계산합니다. "
                    + "같은 초안·일정·카테고리 재전송은 기존 설정을 반환하고, 다른 조건이면 409입니다.")
    ApiResponse<RecurringSettlementWebResponse> create(Long groupId, CreateRecurringSettlementWebRequest request);

    @Operation(summary = "그룹 반복 정산 목록",
            description = "활성 그룹 멤버가 조회합니다. scope=ALL(기본)/PARTICIPATING/OTHER, status=ACTIVE(기본)/CANCELLED, "
                    + "limit 기본 20·최대 100, cursor는 생성일 내림차순 페이지입니다. 요약은 회차당 금액이며 myAmount는 "
                    + "비참여 시 null입니다.")
    ApiResponse<RecurringSettlementPageWebResponse<RecurringSettlementSummaryWebResponse>> list(Long groupId,
            RecurringSettlementScope scope, RecurringSettlementStatusFilter status, UUID cursor, int limit);

    @Operation(summary = "반복 정산 일정 미리보기",
            description = "생성 전 주기와 첫 정산일을 정규화해 첫 UTC 실행 시각과 시간대를 돌려줍니다. "
                    + "firstRunDate를 생략하면 가장 가까운 미래 유효 일정을 계산합니다. 읽기 전용입니다.")
    ApiResponse<RecurringSettlementSchedulePreviewWebResponse> preview(Long groupId,
            RecurrenceSchedule.Frequency frequency, DayOfWeek dayOfWeek, Integer dayOfMonth, LocalDate firstRunDate);

    @Operation(summary = "반복 정산 상세",
            description = "활성 그룹 멤버 또는 생성자가 조회합니다. 생성자는 그룹 탈퇴·삭제 후에도 조회할 수 있습니다. 시각 필드는 UTC입니다.")
    ApiResponse<RecurringSettlementWebResponse> get(Long groupId, UUID id);

    @Operation(summary = "반복 정산 발행 이력",
            description = "예정 시각 내림차순의 회차별 정산을 조회합니다. 이미 발행된 회차는 해지 후에도 유지됩니다. 시각 필드는 UTC입니다.")
    ApiResponse<RecurringSettlementPageWebResponse<RecurringSettlementOccurrenceWebResponse>> occurrences(Long groupId,
            UUID id, UUID cursor, int limit);

    @Operation(summary = "반복 정산 해지",
            description = "생성자만 이후 회차를 해지할 수 있습니다. 동일 해지는 멱등이며 기존 정산·입금·알림은 유지됩니다.")
    ApiResponse<RecurringSettlementWebResponse> cancel(Long groupId, UUID id);
}
