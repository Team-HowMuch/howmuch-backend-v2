package com.amateur.howmuchbackend.adapter.in.web.certificate.docs;

import com.amateur.howmuchbackend.adapter.in.web.certificate.dto.request.DecideCertificateConnectionWebRequest;
import com.amateur.howmuchbackend.adapter.in.web.certificate.dto.request.ReserveCertificateClaimWebRequest;
import com.amateur.howmuchbackend.adapter.in.web.certificate.dto.response.CertificateClaimWebResponse;
import com.amateur.howmuchbackend.adapter.in.web.certificate.dto.response.CertificateConnectionDecisionWebResponse;
import com.amateur.howmuchbackend.adapter.in.web.common.ApiResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.util.UUID;

@Tag(name = "공동인증서 모바일 연결")
public interface CertificateClaimDocs {
    @Operation(
            summary = "연결 금융사와 승인 처리 상태 확인",
            description =
                    "예약자 JWT가 필요합니다. BOUND일 때 connectionId로 연결 계좌를 조회할 수 있습니다. connectedId 원문은 반환하지"
                        + " 않습니다.",
            security = @SecurityRequirement(name = "bearerAuth"))
    ApiResponse<CertificateConnectionDecisionWebResponse> getDecision(UUID sessionId, UUID claimId);

    @Operation(
            summary = "금융정보 연결 Yes/No 선택",
            description =
                    "approved=true이면 기존 연결에 금융사를 추가/갱신합니다. false이면 취소합니다. 실제 저장 완료는 BOUND 상태로"
                        + " 확인합니다. 예약자만 만료 전 선택할 수 있고 동일 결정 재요청은 멱등입니다.",
            security = @SecurityRequirement(name = "bearerAuth"))
    ApiResponse<CertificateConnectionDecisionWebResponse> decide(
            UUID sessionId, UUID claimId, DecideCertificateConnectionWebRequest request);

    @Operation(
            summary = "QR 일회용 연결 예약",
            description =
                    "JWT 인증이 필요합니다. 동일 idempotencyKey 재요청은 같은 예약과 확인 코드를 반환합니다. 예약은 계정 귀속 승인이"
                            + " 아닙니다.",
            security = @SecurityRequirement(name = "bearerAuth"))
    ApiResponse<CertificateClaimWebResponse> reserve(ReserveCertificateClaimWebRequest request);
}
