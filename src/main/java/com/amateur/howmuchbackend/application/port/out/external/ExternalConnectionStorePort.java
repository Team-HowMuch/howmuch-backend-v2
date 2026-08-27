package com.amateur.howmuchbackend.application.port.out.external;

import com.amateur.howmuchbackend.domain.external.ConnectedInstitution;
import com.amateur.howmuchbackend.domain.external.ExternalConnection;

import java.util.Optional;

public interface ExternalConnectionStorePort {
    void validateStorageConfiguration();

    Optional<ExternalConnection> findActiveByUserId(Long userId);

    Optional<ExternalConnection> findActiveByIdAndUserId(Long connectionId, Long userId);

    boolean hasInstitution(Long connectionId, String organization);

    ExternalConnection saveNew(ExternalConnection connection, ConnectedInstitution institution);

    void saveInstitution(ConnectedInstitution institution);

    int deactivateIncompatibleConnections(String currentFingerprint);
}
