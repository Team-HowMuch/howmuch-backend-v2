package com.amateur.howmuchbackend.application.port.in.certificate;

import com.amateur.howmuchbackend.application.port.in.certificate.command.ReserveCertificateClaimCommand;
import com.amateur.howmuchbackend.application.port.in.certificate.result.CertificateClaimResult;

public interface ReserveCertificateClaimUseCase {
    CertificateClaimResult reserve(ReserveCertificateClaimCommand command);
}
