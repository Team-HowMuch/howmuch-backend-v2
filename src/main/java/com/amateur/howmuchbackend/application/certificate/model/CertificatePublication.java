package com.amateur.howmuchbackend.application.certificate.model;

import com.amateur.howmuchbackend.application.port.in.certificate.command.ReceiveCertificateCommand;
import com.amateur.howmuchbackend.application.port.in.certificate.command.ReceiveCertificateResultCommand;

import java.util.UUID;

public record CertificatePublication(
        boolean result,
        UUID messageId,
        UUID leaseToken,
        long generation,
        ReceiveCertificateCommand command,
        ReceiveCertificateResultCommand event) {}
