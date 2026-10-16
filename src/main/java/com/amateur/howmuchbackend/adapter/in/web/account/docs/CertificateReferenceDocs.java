package com.amateur.howmuchbackend.adapter.in.web.account.docs;

import com.amateur.howmuchbackend.adapter.in.web.account.dto.request.StartCertificateReferenceWebRequest;
import com.amateur.howmuchbackend.adapter.in.web.account.dto.response.*;
import com.amateur.howmuchbackend.adapter.in.web.common.ApiResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.util.UUID;

@Tag(name = "계좌 API")
@SecurityRequirement(name = "bearerAuth")
public interface CertificateReferenceDocs {
    @Operation(
            summary = "기존 공동인증서 참조 후보 조회",
            description =
                    "우리 서버에 공동인증서 로그인 성공으로 기록된 본인 은행을 조회합니다. 후보 조회는 CODEF를 호출하지 않습니다. 대상 기관코드를 전달하며"
                        + " 인증서 발급기관을 뜻하지 않습니다. 기존 인증 방식 미확인 데이터는 제외하며, 실제 참조 연결 시에는 CODEF에서 다시"
                        + " 확인합니다. 조회만으로 기관 추가를 실행하지 않습니다. 기능 비활성 시 API가 등록되지 않습니다.")
    ApiResponse<CertificateReferencesWebResponse> references(
            @Pattern(regexp = "^[0-9]{4}$") String organization);

    @Operation(
            summary = "공동인증서 참조 금융기관 연결 작업 접수",
            description =
                    "기존 connectedId에 다른 기관을 비동기로 추가합니다. 사용자 승인 후 호출하며 Idempotency-Key(UUID)가 필수입니다."
                        + " HTTP 202는 접수이고 성공이 아닙니다. 같은 키/같은 입력은 같은 작업, 다른 입력/다른 미완료 인증 작업은 409입니다."
                        + " level 3은 계좌 자격정보가 필요합니다. PC 앱이나 인증서 파일을 다시 보내지 않습니다.")
    ApiResponse<CertificateReferenceOperationWebResponse> start(
            @Positive long connectionId,
            UUID idempotencyKey,
            @Valid StartCertificateReferenceWebRequest request);

    @Operation(
            summary = "공동인증서 참조 연결 작업 상태 조회",
            description =
                    "본인 작업만 조회하며 타 사용자 작업은 404입니다. CONNECTED 이후 기존 계좌 조회/등록 API를 사용합니다."
                        + " UNKNOWN/ACTION_REQUIRED는 새 참조 요청을 보내지 않습니다. 화면 종료가 원격 작업 취소를 뜻하지 않습니다."
                        + " SMS 입력 API는 별도 기능입니다.")
    ApiResponse<CertificateReferenceOperationWebResponse> operation(UUID operationId);
}
