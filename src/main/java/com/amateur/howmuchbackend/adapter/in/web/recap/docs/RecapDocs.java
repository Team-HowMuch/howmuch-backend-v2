package com.amateur.howmuchbackend.adapter.in.web.recap.docs;

import com.amateur.howmuchbackend.adapter.in.web.recap.dto.response.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;

@Tag(name = "리캡 API")
@SecurityRequirement(name = "bearerAuth")
public interface RecapDocs {
    @Operation(summary = "저장된 월 리캡 목록", description = "본인의 저장된 월을 최신순으로 반환합니다. 없으면 빈 months 배열입니다.")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "조회 성공", useReturnTypeSchema = true),
            @ApiResponse(responseCode = "401", description = "UNAUTHORIZED - 인증 필요 또는 유효하지 않은 세션")})
    com.amateur.howmuchbackend.adapter.in.web.common.ApiResponse<RecapMonthsWebResponse> getMonths();

    @Operation(summary = "본인 월 리캡 상세", description = "생성 시점에 고정한 본인 부담액·전월 비교·동행자·카테고리 TOP3·의외의 기록을 조회합니다. "
            + "완료 월과 요일은 KST, 생성 시각은 UTC입니다. 카테고리는 본인 수정값 우선이며 미분류를 제외합니다. "
            + "categories·record·benefits는 자료가 없으면 null이며 기존 리캡을 다시 계산하지 않습니다. 제휴 혜택은 데이터 준비 전까지 null입니다. "
            + "가장 빠른 정산은 유효한 링크 생성 시각이 있는 정산만 비교하며, 분 미만을 버립니다.")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "조회 성공", useReturnTypeSchema = true),
            @ApiResponse(responseCode = "400", description = "INVALID_ARGUMENT - 0001-01~9999-12 yyyy-MM 형식 필요"),
            @ApiResponse(responseCode = "404", description = "RECAP_NOT_FOUND - 현재·미래 월을 포함해 저장된 리캡 없음"),
            @ApiResponse(responseCode = "401", description = "UNAUTHORIZED - 인증 필요 또는 유효하지 않은 세션")})
    com.amateur.howmuchbackend.adapter.in.web.common.ApiResponse<MonthlyRecapWebResponse> getRecap(
            @Parameter(required = true, description = "yyyy-MM", schema = @Schema(pattern = "[0-9]{4}-(0[1-9]|1[0-2])", example = "2026-09")) String yearMonth);

    @Operation(summary = "본인 월 리캡 공유 데이터", description = "본인 이름·공개 ID·사진을 제외합니다. 회원 동행자는 생성 당시 publicId, 비회원은 가린 이름입니다. 사진 주소에는 본인 인증이 필요합니다.")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "조회 성공", useReturnTypeSchema = true),
            @ApiResponse(responseCode = "400", description = "INVALID_ARGUMENT - yyyy-MM 형식 필요"),
            @ApiResponse(responseCode = "404", description = "RECAP_NOT_FOUND - 저장된 리캡 없음"),
            @ApiResponse(responseCode = "401", description = "UNAUTHORIZED - 인증 필요 또는 유효하지 않은 세션")})
    com.amateur.howmuchbackend.adapter.in.web.common.ApiResponse<SharedMonthlyRecapWebResponse> getShare(
            @Parameter(required = true, description = "0001-01~9999-12 yyyy-MM", example = "2026-09") String yearMonth);

    @Operation(summary = "본인 리캡의 고정 동행자 사진", description = "저장된 사진 복사본을 바이너리로 반환합니다. 사진 없음은 기본 표시를 위해 avatarUrl=null이며 직접 호출은 404입니다.")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "사진 조회 성공", content = {
            @Content(mediaType = "image/png", schema = @Schema(type = "string", format = "binary")),
            @Content(mediaType = "image/jpeg", schema = @Schema(type = "string", format = "binary")),
            @Content(mediaType = "image/webp", schema = @Schema(type = "string", format = "binary"))}),
            @ApiResponse(responseCode = "400", description = "INVALID_ARGUMENT - yyyy-MM 형식 필요"),
            @ApiResponse(responseCode = "404", description = "RECAP_NOT_FOUND / RECAP_IMAGE_NOT_FOUND"),
            @ApiResponse(responseCode = "401", description = "UNAUTHORIZED - 인증 필요 또는 유효하지 않은 세션")})
    ResponseEntity<byte[]> getCompanionAvatar(@Parameter(required = true, example = "2026-09") String yearMonth);
}
