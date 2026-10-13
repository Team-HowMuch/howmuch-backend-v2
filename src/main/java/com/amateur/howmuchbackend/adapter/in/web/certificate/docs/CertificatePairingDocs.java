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

    @Operation(summary = "PC 연결·금융 인증 상태 조회")
    ApiResponse<CertificatePairingWebResponse> pc(UUID id, String capability);

    @Operation(summary = "PC 연결 취소")
    ApiResponse<Void> cancel(UUID id, String capability);

    @Operation(summary = "모바일 QR 연결", description = "JWT 필수. 최초 로그인 사용자에 고정되며 최종 금융 연결 승인은 아닙니다.")
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
            description = "응답의 기존 등록 id와 PC/upload capability로 업로드합니다. 인증 성공 전에는 최종 승인 요청이 거절됩니다.")
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
