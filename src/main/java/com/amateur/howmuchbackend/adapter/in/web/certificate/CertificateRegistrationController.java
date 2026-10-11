package com.amateur.howmuchbackend.adapter.in.web.certificate;

import com.amateur.howmuchbackend.adapter.in.web.certificate.docs.CertificateRegistrationDocs;
import com.amateur.howmuchbackend.adapter.in.web.certificate.dto.request.*;
import com.amateur.howmuchbackend.adapter.in.web.certificate.dto.response.*;
import com.amateur.howmuchbackend.adapter.in.web.common.ApiResponse;
import com.amateur.howmuchbackend.application.port.in.certificate.*;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v2/certificate-registrations")
@ConditionalOnProperty(prefix = "howmuch.certificate", name = "enabled", havingValue = "true")
public class CertificateRegistrationController implements CertificateRegistrationDocs {
    private final CreateCertificateRegistrationUseCase create;
    private final SubmitCertificateUploadUseCase submit;
    private final GetCertificateRegistrationUseCase get;
    private final CancelCertificateRegistrationUseCase cancel;
    private final GetCertificateInstitutionsUseCase catalog;
    private final IssueCertificateQrUseCase qr;
    private final InspectCertificateClaimUseCase claims;

    @GetMapping("/institutions")
    public ApiResponse<List<CertificateInstitutionWebResponse>> institutions() {
        return ApiResponse.success(
                catalog.institutions().stream()
                        .map(CertificateInstitutionWebResponse::from)
                        .toList());
    }

    @PostMapping
    public ApiResponse<CertificateRegistrationWebResponse> create(
            @Valid @RequestBody CreateCertificateRegistrationWebRequest r,
            HttpServletRequest http) {
        return ApiResponse.created(
                CertificateRegistrationWebResponse.from(
                        create.create(r.toCommand(http.getRemoteAddr()))));
    }

    @PostMapping("/{id}/upload")
    public ApiResponse<CertificateRegistrationWebResponse> submit(
            @PathVariable UUID id,
            @RequestHeader("X-PC-Capability") String pc,
            @RequestHeader("X-Upload-Capability") String upload,
            @Valid @RequestBody SubmitCertificateUploadWebRequest r) {
        return ApiResponse.success(
                CertificateRegistrationWebResponse.from(
                        submit.submit(r.toCommand(id, pc, upload))));
    }

    @GetMapping("/{id}")
    public ApiResponse<CertificateRegistrationWebResponse> get(
            @PathVariable UUID id, @RequestHeader("X-PC-Capability") String pc) {
        return ApiResponse.success(CertificateRegistrationWebResponse.from(get.get(id, pc)));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> cancel(
            @PathVariable UUID id, @RequestHeader("X-PC-Capability") String pc) {
        cancel.cancel(id, pc);
        return ApiResponse.success();
    }

    @PostMapping("/{id}/qr")
    public ApiResponse<CertificateQrWebResponse> qr(
            @PathVariable UUID id, @RequestHeader("X-PC-Capability") String pc) {
        return ApiResponse.success(CertificateQrWebResponse.from(qr.issue(id, pc)));
    }

    @GetMapping("/{id}/claim")
    public ApiResponse<CertificateClaimWebResponse> claim(
            @PathVariable UUID id, @RequestHeader("X-PC-Capability") String pc) {
        return ApiResponse.success(CertificateClaimWebResponse.from(claims.inspect(id, pc)));
    }

    @DeleteMapping("/{id}/claims/{claimId}")
    public ApiResponse<Void> reject(
            @PathVariable UUID id,
            @RequestHeader("X-PC-Capability") String pc,
            @PathVariable UUID claimId) {
        claims.reject(id, pc, claimId);
        return ApiResponse.success();
    }
}
