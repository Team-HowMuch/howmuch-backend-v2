package com.amateur.howmuchbackend.adapter.in.web.certificate.docs;

import com.amateur.howmuchbackend.adapter.in.web.certificate.dto.request.*;
import com.amateur.howmuchbackend.adapter.in.web.certificate.dto.response.*;
import com.amateur.howmuchbackend.adapter.in.web.common.ApiResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.servlet.http.HttpServletRequest;

import java.util.*;

@Tag(name = "공동인증서 PC 등록")
public interface CertificateRegistrationDocs {
    @Operation(summary = "공동인증서 등록 금융사 조회")
    ApiResponse<List<CertificateInstitutionWebResponse>> institutions();

    @Operation(
            summary = "PC 등록 세션 생성",
            description = "PC 로그인 없이 생성합니다. 반환된 capability는 한 번만 제공하며 안전하게 보관해야 합니다.")
    ApiResponse<CertificateRegistrationWebResponse> create(
            CreateCertificateRegistrationWebRequest request, HttpServletRequest http);

    @Operation(
            summary = "일회용 인증서 업로드",
            description = "파일은 Base64 JSON body, PC와 upload capability는 각각 전용 header로 전달합니다.")
    ApiResponse<CertificateRegistrationWebResponse> submit(
            UUID id, String pc, String upload, SubmitCertificateUploadWebRequest request);

    @Operation(summary = "PC 등록 상태 조회", description = "실패 시 failureCode는 서버 공통 코드, failureMessage는 사용자 안내입니다. UNKNOWN은 금융사 결과 확인이 필요하며 자동 재등록하지 않습니다.")
    ApiResponse<CertificateRegistrationWebResponse> get(UUID id, String pc);

    @Operation(summary = "승인 전 등록 취소")
    ApiResponse<Void> cancel(UUID id, String pc);

    @Operation(summary = "60초 QR 발급 또는 회전")
    ApiResponse<CertificateQrWebResponse> qr(UUID id, String pc);

    @Operation(summary = "PC 예약 확인 코드 조회")
    ApiResponse<CertificateClaimWebResponse> claim(UUID id, String pc);

    @Operation(summary = "모바일 연결 예약 거절")
    ApiResponse<Void> reject(UUID id, String pc, UUID claimId);
}
