package com.amateur.howmuchbackend.adapter.in.web.certificate.dto.request;

import jakarta.validation.constraints.*;

public record ClaimCertificatePairingWebRequest(
        @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{43}") String qrToken) {}
