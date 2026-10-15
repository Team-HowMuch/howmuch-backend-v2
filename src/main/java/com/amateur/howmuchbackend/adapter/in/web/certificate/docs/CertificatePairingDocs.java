package com.amateur.howmuchbackend.adapter.in.web.certificate.docs;

import com.amateur.howmuchbackend.adapter.in.web.certificate.dto.request.*;
import com.amateur.howmuchbackend.adapter.in.web.certificate.dto.response.*;
import com.amateur.howmuchbackend.adapter.in.web.common.ApiResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.servlet.http.HttpServletRequest;

import java.util.UUID;

@Tag(
        name = "인증서 초기 PC·모바일 연결",
        description = "첫 QR 스캔은 기기 연결만 의미합니다. PC 금융 인증 후 기존 decision API로 최종 승인합니다.")
public interface CertificatePairingDocs {
    @Operation(
            summary = "초기 연결 QR 생성",
            description =
                    "PC/QR capability는 이 응답에서만 반환합니다. QR v2 HOWMUCH_CERTIFICATE_PAIRING의"
                            + " pairingId/qrToken을 사용합니다.")
    ApiResponse<CertificatePairingWebResponse> create(HttpServletRequest request);

    @Operation(summary = "PC 연결·금융 인증 상태 조회", description = "모바일 연결 후 organization을 반환합니다. PC는 해당 은행을 선택·비활성화하고 입력 조건을 표시합니다.")
    ApiResponse<CertificatePairingWebResponse> pc(UUID id, String capability);

    @Operation(summary = "PC 연결 취소")
    ApiResponse<Void> cancel(UUID id, String capability);

    @Operation(summary = "모바일 연결 취소", description = "JWT 소유자만 호출합니다. 화면 종료 시 호출하며 반복 취소는 멱등입니다. 승인 완료/결과 불명 상태는 기존 취소 보호 규칙을 따릅니다.")
    ApiResponse<Void> cancelMobile(UUID id);

    @Operation(summary = "모바일 QR 연결", description = "JWT 필수. qrToken과 모바일에서 선택한 organization(4자리)을 필수로 전송합니다. 최초 사용자와 은행에 고정됩니다. 같은 은행 재요청은 멱등이며 은행 변경은 409입니다. 최종 금융 연결 승인은 아닙니다.")
    ApiResponse<CertificatePairingWebResponse> claim(
            UUID id, ClaimCertificatePairingWebRequest request);

    @Operation(
            summary = "모바일 대기·인증 결과 조회",
            description =
                    "JWT 소유자만 조회합니다. PAIRED는 PC 입력 대기, SUBMITTED/REGISTERING은 인증 중,"
                        + " READY_FOR_CLAIM은 최종 승인 가능, FAILED/UNKNOWN은 failureCode/failureMessage를"
                        + " 표시합니다.")
    ApiResponse<CertificatePairingWebResponse> mobile(UUID id);

    @Operation(
            summary = "연결된 PC에서 금융사 등록 시작",
            description = "모바일에서 선택한 organization과 일치해야 합니다. 미지정·불일치는 409입니다. 응답의 등록 id와 PC/upload capability로 업로드합니다. 미승인 FAILED 세션은 동일 휴대폰 연결에서 재시도할 수 있습니다. UNKNOWN/진행 중/승인된 세션은 재시도하지 않습니다.")
    ApiResponse<CertificateRegistrationWebResponse> registration(
            UUID id,
            String capability,
            CreateCertificateRegistrationWebRequest request,
            HttpServletRequest http);

    @Operation(
            summary = "인증 완료 후 최종 승인 예약",
            description =
                    "JWT 소유자만 호출합니다. 반환 claimId와 pairing 조회의 sessionId로 기존"
                            + " /certificate-claims/{sessionId}/{claimId}/decision에 Yes/No를 전송합니다.")
    ApiResponse<CertificateClaimWebResponse> approval(UUID id);
}
