package com.amateur.howmuchbackend.adapter.in.web.certificate;

import com.amateur.howmuchbackend.adapter.in.web.certificate.docs.CertificatePairingDocs;
import com.amateur.howmuchbackend.adapter.in.web.certificate.dto.request.*;
import com.amateur.howmuchbackend.adapter.in.web.certificate.dto.response.*;
import com.amateur.howmuchbackend.adapter.in.web.common.ApiResponse;
import com.amateur.howmuchbackend.application.port.in.certificate.*;
import com.amateur.howmuchbackend.application.port.in.certificate.command.StartPairedCertificateRegistrationCommand;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v2/certificate-pairings")
@ConditionalOnProperty(prefix = "howmuch.certificate", name = "enabled", havingValue = "true")
public class CertificatePairingController implements CertificatePairingDocs {
    private final CreateCertificatePairingUseCase createPairing;
    private final GetCertificatePairingUseCase getPairing;
    private final PairCertificateDeviceUseCase pairDevice;
    private final StartPairedCertificateRegistrationUseCase startRegistration;
    private final ReservePairedCertificateClaimUseCase reserveClaim;
    private final CancelCertificatePairingUseCase cancelPairing;

    @PostMapping
    public ApiResponse<CertificatePairingWebResponse> create(HttpServletRequest r) {
        return ApiResponse.created(
                CertificatePairingWebResponse.from(createPairing.createPairing(r.getRemoteAddr())));
    }

    @GetMapping("/{id}/pc")
    public ApiResponse<CertificatePairingWebResponse> pc(
            @PathVariable UUID id, @RequestHeader("X-PC-Capability") String pc) {
        return ApiResponse.success(
                CertificatePairingWebResponse.from(getPairing.getPcPairing(id, pc)));
    }

    @DeleteMapping("/{id}/pc")
    public ApiResponse<Void> cancel(
            @PathVariable UUID id, @RequestHeader("X-PC-Capability") String pc) {
        cancelPairing.cancelPairing(id, pc);
        return ApiResponse.success();
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> cancelMobile(@PathVariable UUID id) {
        cancelPairing.cancelMobilePairing(id);
        return ApiResponse.success();
    }

    @PostMapping("/{id}/claim")
    public ApiResponse<CertificatePairingWebResponse> claim(
            @PathVariable UUID id, @Valid @RequestBody ClaimCertificatePairingWebRequest r) {
        return ApiResponse.success(
                CertificatePairingWebResponse.from(pairDevice.pair(r.toCommand(id))));
    }

    @GetMapping("/{id}")
    public ApiResponse<CertificatePairingWebResponse> mobile(@PathVariable UUID id) {
        return ApiResponse.success(
                CertificatePairingWebResponse.from(getPairing.getMobilePairing(id)));
    }

    @PostMapping("/{id}/registrations")
    public ApiResponse<CertificateRegistrationWebResponse> registration(
            @PathVariable UUID id,
            @RequestHeader("X-PC-Capability") String pc,
            @Valid @RequestBody CreateCertificateRegistrationWebRequest r,
            HttpServletRequest http) {
        return ApiResponse.created(
                CertificateRegistrationWebResponse.from(
                        startRegistration.createRegistration(
                                new StartPairedCertificateRegistrationCommand(
                                        id, pc, r.organization(), http.getRemoteAddr()))));
    }

    @PostMapping("/{id}/approval")
    public ApiResponse<CertificateClaimWebResponse> approval(@PathVariable UUID id) {
        return ApiResponse.success(
                CertificateClaimWebResponse.from(reserveClaim.requestApproval(id)));
    }
}
