package com.amateur.howmuchbackend.application.port.in.certificate.command;

import java.time.LocalDateTime;
import java.util.UUID;

public record ReceiveCertificateResultCommand(
        UUID messageId,
        UUID sessionId,
        long version,
        String type,
        UUID connectionRef,
        String failureCode,
        LocalDateTime occurredAt) {}
