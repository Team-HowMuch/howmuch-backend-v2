package com.amateur.howmuchbackend.adapter.in.web.certificate;

import com.amateur.howmuchbackend.adapter.in.web.certificate.docs.CertificateClaimDocs;
import com.amateur.howmuchbackend.adapter.in.web.certificate.dto.request.DecideCertificateConnectionWebRequest;
import com.amateur.howmuchbackend.adapter.in.web.certificate.dto.request.ReserveCertificateClaimWebRequest;
import com.amateur.howmuchbackend.adapter.in.web.certificate.dto.response.CertificateClaimWebResponse;
import com.amateur.howmuchbackend.adapter.in.web.certificate.dto.response.CertificateConnectionDecisionWebResponse;
import com.amateur.howmuchbackend.adapter.in.web.common.ApiResponse;
import com.amateur.howmuchbackend.application.port.in.certificate.DecideCertificateConnectionUseCase;
import com.amateur.howmuchbackend.application.port.in.certificate.GetCertificateConnectionDecisionUseCase;
import com.amateur.howmuchbackend.application.port.in.certificate.ReserveCertificateClaimUseCase;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v2/certificate-claims")
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "howmuch.certificate", name = "enabled", havingValue = "true")
public class CertificateClaimController implements CertificateClaimDocs {
    private final ReserveCertificateClaimUseCase reserve;
    private final DecideCertificateConnectionUseCase decision;
    private final GetCertificateConnectionDecisionUseCase query;

    @GetMapping("/{sessionId}/{claimId}")
    public ApiResponse<CertificateConnectionDecisionWebResponse> getDecision(
            @PathVariable UUID sessionId, @PathVariable UUID claimId) {
        return ApiResponse.success(
                CertificateConnectionDecisionWebResponse.from(
                        query.getDecision(sessionId, claimId)));
    }

    @PostMapping("/{sessionId}/{claimId}/decision")
    public ApiResponse<CertificateConnectionDecisionWebResponse> decide(
            @PathVariable UUID sessionId,
            @PathVariable UUID claimId,
            @Valid @RequestBody DecideCertificateConnectionWebRequest request) {
        return ApiResponse.success(
                CertificateConnectionDecisionWebResponse.from(
                        decision.decide(request.toCommand(sessionId, claimId))));
    }

    @PostMapping
    public ApiResponse<CertificateClaimWebResponse> reserve(
            @Valid @RequestBody ReserveCertificateClaimWebRequest request) {
        return ApiResponse.created(
                CertificateClaimWebResponse.from(reserve.reserve(request.toCommand())));
    }
}
