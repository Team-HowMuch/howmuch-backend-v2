package com.amateur.howmuchbackend.adapter.in.web.external.docs;

import com.amateur.howmuchbackend.adapter.in.web.common.ApiResponse;
import com.amateur.howmuchbackend.adapter.in.web.external.dto.request.SubmitAdditionalAuthenticationSmsWebRequest;
import com.amateur.howmuchbackend.adapter.in.web.external.dto.response.AdditionalAuthenticationWebResponse;

import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.util.UUID;

@Tag(name = "금융 SMS 추가 인증")
@SecurityRequirement(name = "bearerAuth")
public interface AdditionalAuthenticationDocs {
    @Operation(
            summary = "내 활성 추가 인증 목록",
            description = "앱 재실행/foreground 복귀 시 소유 사용자의 대기 중인 인증을 확인합니다.")
    ApiResponse<java.util.List<AdditionalAuthenticationWebResponse>> list();

    @Operation(
            summary = "추가 인증 상태 조회",
            description =
                    "소유 사용자 JWT 필수. 타인/미존재 404. retryChallengeId가 있으면 해당 후속 인증을 조회합니다. 앱 이탈로는 취소되지"
                        + " 않습니다.")
    ApiResponse<AdditionalAuthenticationWebResponse> get(UUID id);

    @Operation(
            summary = "SMS 번호 접수",
            description =
                    "202 접수 후 GET으로 조회. 동일 submissionId는 멱등, 다른 제출은 409, 만료는 410. 인증서 SMS 성공 후에도 최종"
                            + " 금융 연결 승인이 필요합니다.")
    ApiResponse<AdditionalAuthenticationWebResponse> submit(
            UUID id, SubmitAdditionalAuthenticationSmsWebRequest request);

    @Operation(
            summary = "추가 인증 취소",
            description =
                    "인증서 작업이면 pairing도 취소합니다. 계좌 작업은 해당 조회/연결 인증만 취소합니다. 요청 전송 중이면"
                            + " cancelRequested=true이며 늦은 결과를 저장하지 않습니다.")
    ApiResponse<AdditionalAuthenticationWebResponse> cancel(UUID id);
}
