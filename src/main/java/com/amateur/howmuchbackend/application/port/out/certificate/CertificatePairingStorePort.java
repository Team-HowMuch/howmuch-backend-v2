package com.amateur.howmuchbackend.application.port.out.certificate;

import com.amateur.howmuchbackend.application.certificate.model.*;

import java.time.LocalDateTime;
import java.util.*;

public interface CertificatePairingStorePort {
    long countActive(LocalDateTime now);

    boolean hasUnresolvedAuthentication(long user, UUID session);

    void create(CertificatePairing pairing, LocalDateTime now);

    Optional<CertificatePairing> find(UUID id, boolean lock);

    Optional<CertificatePairing> findBySession(UUID session);

    void pair(UUID id, long user, String organization, LocalDateTime now);

    void link(UUID id, UUID session, String registrationPcHash);

    void prepare(UUID session, Long existingConnectionId);

    void cancel(UUID id, LocalDateTime now);
}
