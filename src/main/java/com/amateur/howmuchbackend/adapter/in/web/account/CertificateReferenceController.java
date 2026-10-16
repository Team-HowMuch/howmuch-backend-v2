package com.amateur.howmuchbackend.adapter.in.web.account;

import com.amateur.howmuchbackend.adapter.in.web.account.docs.CertificateReferenceDocs;
import com.amateur.howmuchbackend.adapter.in.web.account.dto.request.StartCertificateReferenceWebRequest;
import com.amateur.howmuchbackend.adapter.in.web.account.dto.response.*;
import com.amateur.howmuchbackend.adapter.in.web.common.ApiResponse;
import com.amateur.howmuchbackend.application.port.in.account.*;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

import lombok.RequiredArgsConstructor;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v2/accounts")
@ConditionalOnProperty(
        prefix = "howmuch.certificate-reference",
        name = "enabled",
        havingValue = "true")
public class CertificateReferenceController implements CertificateReferenceDocs {
    private final GetCertificateReferencesUseCase references;
    private final StartCertificateReferenceConnectionUseCase start;
    private final GetCertificateReferenceOperationUseCase operations;

    @GetMapping("/certificate-references")
    public ApiResponse<CertificateReferencesWebResponse> references(
            @RequestParam @Pattern(regexp = "^[0-9]{4}$") String organization) {
        return ApiResponse.success(
                CertificateReferencesWebResponse.from(
                        references.getCertificateReferences(organization)));
    }

    @PostMapping("/connections/{connectionId}/certificate-references")
    public ApiResponse<CertificateReferenceOperationWebResponse> start(
            @PathVariable @Positive long connectionId,
            @RequestHeader("Idempotency-Key") UUID idempotencyKey,
            @Valid @RequestBody StartCertificateReferenceWebRequest request) {
        return ApiResponse.of(
                202,
                CertificateReferenceOperationWebResponse.from(
                        start.startCertificateReference(
                                request.toCommand(connectionId, idempotencyKey))));
    }

    @GetMapping("/certificate-reference-operations/{operationId}")
    public ApiResponse<CertificateReferenceOperationWebResponse> operation(
            @PathVariable UUID operationId) {
        return ApiResponse.success(
                CertificateReferenceOperationWebResponse.from(
                        operations.getCertificateReferenceOperation(operationId)));
    }
}
