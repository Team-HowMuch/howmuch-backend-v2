package com.amateur.howmuchbackend.application.port.in.certificate.command;

import java.util.UUID;

public record DecideCertificateConnectionCommand(UUID sessionId, UUID claimId, boolean approved) {}
