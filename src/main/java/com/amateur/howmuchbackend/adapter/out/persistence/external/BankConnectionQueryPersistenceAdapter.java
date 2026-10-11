package com.amateur.howmuchbackend.adapter.out.persistence.external;

import com.amateur.howmuchbackend.adapter.out.persistence.external.repository.ConnectedInstitutionJpaRepository;
import com.amateur.howmuchbackend.application.account.model.BankConnectionSummary;
import com.amateur.howmuchbackend.application.port.out.external.LoadBankConnectionsPort;
import com.amateur.howmuchbackend.infra.codef.config.CodefConfigurationFingerprint;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

@Component
@RequiredArgsConstructor
public class BankConnectionQueryPersistenceAdapter implements LoadBankConnectionsPort {
    private final ConnectedInstitutionJpaRepository institutions;
    private final CodefConfigurationFingerprint fingerprint;

    @Override
    @Transactional(readOnly = true)
    public List<BankConnectionSummary> findActiveByUserId(Long userId) {
        return institutions.findActiveSummaries(userId, fingerprint.current());
    }
}
