package com.amateur.howmuchbackend.adapter.out.persistence.external.mapper;

import com.amateur.howmuchbackend.adapter.out.persistence.external.CodefSecretCipher;
import com.amateur.howmuchbackend.adapter.out.persistence.external.entity.ExternalConnectionJpaEntity;
import com.amateur.howmuchbackend.domain.external.ExternalConnection;

public final class ExternalConnectionMapper {
    private ExternalConnectionMapper() {
    }

    public static ExternalConnection toDomain(ExternalConnectionJpaEntity entity, CodefSecretCipher cipher) {
        return new ExternalConnection(
                entity.getId(),
                entity.getUser().getId(),
                cipher.decryptConnectedId(entity.getUser().getId(), entity.getConnectedIdCiphertext())
        );
    }
}
