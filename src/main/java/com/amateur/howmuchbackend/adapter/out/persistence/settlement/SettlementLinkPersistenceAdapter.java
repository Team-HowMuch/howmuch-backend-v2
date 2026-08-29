package com.amateur.howmuchbackend.adapter.out.persistence.settlement;

import com.amateur.howmuchbackend.adapter.out.persistence.external.CodefSecretCipher;
import com.amateur.howmuchbackend.adapter.out.persistence.settlement.mapper.SettlementRequestMapper;
import com.amateur.howmuchbackend.adapter.out.persistence.settlement.repository.SettlementLinkJpaRepository;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementLinkPort;
import com.amateur.howmuchbackend.application.port.out.settlement.SettlementShareLinkStorePort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.settlement.SettlementLink;
import com.amateur.howmuchbackend.domain.settlement.status.SettlementStatusCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class SettlementLinkPersistenceAdapter implements
        LoadSettlementLinkPort,
        SettlementShareLinkStorePort {
    private final SettlementLinkJpaRepository linkRepository;
    private final CodefSecretCipher cipher;

    @Override
    @Transactional(readOnly = true)
    public Optional<SettlementLink> findByTokenHash(String tokenHash) {
        return linkRepository.findByTokenHash(tokenHash)
                .map(SettlementRequestMapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<StoredSettlementShareLink> findBySettlementId(UUID settlementId) {
        return linkRepository.findBySettlementId(settlementId)
                .map(entity -> new StoredSettlementShareLink(
                        SettlementRequestMapper.toDomain(entity),
                        entity.getTokenCiphertext() == null
                                ? null
                                : cipher.decryptSettlementLinkToken(
                                        settlementId,
                                        entity.getTokenCiphertext()
                                )
                ));
    }

    @Override
    public void replace(SettlementLink link, String rawToken) {
        var entity = linkRepository.findBySettlementId(link.settlementId())
                .filter(candidate -> candidate.getId().equals(link.id()))
                .orElseThrow(() -> ApplicationException.of(
                        SettlementStatusCode.SETTLEMENT_LINK_NOT_FOUND
                ));
        entity.replaceToken(
                link.tokenHash(),
                cipher.encryptSettlementLinkToken(link.settlementId(), rawToken),
                link.expiresAt()
        );
        linkRepository.saveAndFlush(entity);
    }
}
