package com.amateur.howmuchbackend.application.port.out.certificate;

import java.util.UUID;

public interface CertificateCapabilityPort {
    String mint();

    String hash(String raw);

    boolean matches(String raw, String expectedHash);

    String confirmationCode(UUID claimId);
}
