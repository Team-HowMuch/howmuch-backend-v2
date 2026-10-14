package com.amateur.howmuchbackend.application.port.in.certificate;

import com.amateur.howmuchbackend.application.port.in.certificate.result.*;

import com.amateur.howmuchbackend.application.port.in.certificate.command.PairCertificateDeviceCommand;

public interface PairCertificateDeviceUseCase {
    CertificatePairingResult pair(PairCertificateDeviceCommand command);
}
