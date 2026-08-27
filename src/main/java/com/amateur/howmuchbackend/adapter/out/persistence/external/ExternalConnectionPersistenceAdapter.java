package com.amateur.howmuchbackend.adapter.out.persistence.external;

import com.amateur.howmuchbackend.adapter.out.persistence.external.entity.ConnectedInstitutionJpaEntity;
import com.amateur.howmuchbackend.adapter.out.persistence.external.entity.ExternalConnectionJpaEntity;
import com.amateur.howmuchbackend.adapter.out.persistence.external.mapper.ExternalConnectionMapper;
import com.amateur.howmuchbackend.adapter.out.persistence.external.repository.ConnectedInstitutionJpaRepository;
import com.amateur.howmuchbackend.adapter.out.persistence.external.repository.ExternalConnectionJpaRepository;
import com.amateur.howmuchbackend.adapter.out.persistence.user.UserJpaRepository;
import com.amateur.howmuchbackend.application.port.out.external.ExternalConnectionStorePort;
import com.amateur.howmuchbackend.domain.external.ConnectedInstitution;
import com.amateur.howmuchbackend.domain.external.ExternalConnection;
import com.amateur.howmuchbackend.infra.codef.config.CodefConfigurationFingerprint;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class ExternalConnectionPersistenceAdapter implements ExternalConnectionStorePort {
    private final ExternalConnectionJpaRepository connectionRepository;
    private final ConnectedInstitutionJpaRepository institutionRepository;
    private final UserJpaRepository userRepository;
    private final CodefSecretCipher cipher;
    private final CodefConfigurationFingerprint configurationFingerprint;

    @Override
    public void validateStorageConfiguration() {
        cipher.validateConfiguration();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ExternalConnection> findActiveByUserId(Long userId) {
        return connectionRepository.findByUser_IdAndActiveTrueAndConfigurationFingerprint(
                userId, configurationFingerprint.current()
        ).map(entity -> ExternalConnectionMapper.toDomain(entity, cipher));
    }

    @Override
    @Transactional
    public Optional<ExternalConnection> findActiveByIdAndUserId(Long connectionId, Long userId) {
        return connectionRepository.findByIdAndUser_IdAndActiveTrueAndConfigurationFingerprint(
                connectionId, userId, configurationFingerprint.current()
        ).map(entity -> ExternalConnectionMapper.toDomain(entity, cipher));
    }

    @Override
    public boolean hasInstitution(Long connectionId, String organization) {
        return institutionRepository.existsByExternalConnection_IdAndOrganization(connectionId, organization);
    }

    @Override
    @Transactional
    public ExternalConnection saveNew(ExternalConnection connection, ConnectedInstitution institution) {
        String ciphertext = cipher.encryptConnectedId(connection.userId(), connection.connectedId());
        String fingerprint = configurationFingerprint.current();
        ExternalConnectionJpaEntity saved = connectionRepository.findByUser_Id(connection.userId())
                .map(existing -> reactivate(existing, ciphertext, fingerprint))
                .orElseGet(() -> connectionRepository.save(new ExternalConnectionJpaEntity(
                        userRepository.getReferenceById(connection.userId()), ciphertext, fingerprint
                )));
        institutionRepository.save(new ConnectedInstitutionJpaEntity(
                saved, institution.organization(), institution.status(), institution.lastResultCode()
        ));
        return new ExternalConnection(saved.getId(), connection.userId(), connection.connectedId());
    }

    @Override
    @Transactional
    public void saveInstitution(ConnectedInstitution institution) {
        institutionRepository.save(new ConnectedInstitutionJpaEntity(
                connectionRepository.getReferenceById(institution.externalConnectionId()),
                institution.organization(), institution.status(), institution.lastResultCode()
        ));
    }

    @Override
    @Transactional
    public int deactivateIncompatibleConnections(String currentFingerprint) {
        return connectionRepository.deactivateIncompatible(currentFingerprint, LocalDateTime.now(ZoneOffset.UTC));
    }

    private ExternalConnectionJpaEntity reactivate(
            ExternalConnectionJpaEntity connection,
            String ciphertext,
            String fingerprint
    ) {
        institutionRepository.deleteAllByConnectionId(connection.getId());
        connection.reactivate(ciphertext, fingerprint);
        return connection;
    }
}
