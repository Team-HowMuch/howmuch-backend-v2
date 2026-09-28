package com.amateur.howmuchbackend.domain.external;

public record ConnectedInstitution(
        Long id,
        Long externalConnectionId,
        String organization,
        ExternalConnectionStatus status,
        String lastResultCode,
        String localCredentialsCiphertext,
        String kmsCredentialsCiphertext
) {
    @Override
    public String toString() {
        return ("ConnectedInstitution[id=%s, externalConnectionId=%s, organization=%s, status=%s, "
                + "lastResultCode=%s, localCredentialsCiphertext=***, kmsCredentialsCiphertext=***]")
                .formatted(id, externalConnectionId, organization, status, lastResultCode);
    }
}
