package com.amateur.howmuchbackend.application.port.in.certificate.command;

import java.time.LocalDateTime;
import java.util.UUID;

public record ReceiveCertificateCommand(
        UUID messageId,
        UUID sessionId,
        long version,
        String type,
        String organization,
        UUID uploadRef,
        LocalDateTime deadline,
        LocalDateTime occurredAt) {}
