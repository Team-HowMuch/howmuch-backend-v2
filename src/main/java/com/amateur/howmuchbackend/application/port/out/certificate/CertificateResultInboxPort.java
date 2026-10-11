package com.amateur.howmuchbackend.application.port.out.certificate;

import com.amateur.howmuchbackend.application.port.in.certificate.command.ReceiveCertificateResultCommand;

import java.time.LocalDateTime;

public interface CertificateResultInboxPort {
    boolean apply(
            ReceiveCertificateResultCommand event,
            String hash,
            boolean mayApply,
            LocalDateTime now);
}
