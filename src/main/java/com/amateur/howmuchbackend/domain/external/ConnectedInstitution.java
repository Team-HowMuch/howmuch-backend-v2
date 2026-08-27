package com.amateur.howmuchbackend.domain.external;

public record ConnectedInstitution(
        Long id,
        Long externalConnectionId,
        String organization,
        ExternalConnectionStatus status,
        String lastResultCode
) {
}
