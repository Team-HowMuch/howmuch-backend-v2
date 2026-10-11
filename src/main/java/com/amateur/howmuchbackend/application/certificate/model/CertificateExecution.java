package com.amateur.howmuchbackend.application.certificate.model;

import com.amateur.howmuchbackend.application.port.in.certificate.command.ReceiveCertificateCommand;

import java.util.UUID;

public record CertificateExecution(
        ReceiveCertificateCommand command, UUID leaseToken, long generation) {}
