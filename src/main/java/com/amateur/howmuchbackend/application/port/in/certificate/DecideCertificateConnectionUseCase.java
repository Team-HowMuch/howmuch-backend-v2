package com.amateur.howmuchbackend.application.port.in.certificate;

import com.amateur.howmuchbackend.application.port.in.certificate.command.DecideCertificateConnectionCommand;
import com.amateur.howmuchbackend.application.port.in.certificate.result.CertificateConnectionDecisionResult;

public interface DecideCertificateConnectionUseCase {
    CertificateConnectionDecisionResult decide(DecideCertificateConnectionCommand command);
}
