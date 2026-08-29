package com.amateur.howmuchbackend.application.settlement.service;

import com.amateur.howmuchbackend.application.port.in.settlement.GetSettlementShareLinkUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementShareLinkResult;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementPort;
import com.amateur.howmuchbackend.application.port.out.settlement.SettlementLinkTokenPort;
import com.amateur.howmuchbackend.application.port.out.settlement.SettlementShareLinkStorePort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.settlement.Settlement;
import com.amateur.howmuchbackend.domain.settlement.SettlementLink;
import com.amateur.howmuchbackend.domain.settlement.SettlementLinkStatus;
import com.amateur.howmuchbackend.domain.settlement.SettlementStatus;
import com.amateur.howmuchbackend.domain.settlement.status.SettlementStatusCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SettlementShareLinkService implements GetSettlementShareLinkUseCase {
    private final CurrentActorPort currentActorPort;
    private final LoadSettlementPort loadSettlementPort;
    private final SettlementShareLinkStorePort shareLinkStorePort;
    private final SettlementLinkTokenPort linkTokenPort;

    @Override
    @Transactional
    public SettlementShareLinkResult get(UUID settlementId) {
        Long actorId = currentActorPort.getCurrentActor().userId();
        Settlement settlement = loadSettlementPort.findByIdForUpdate(settlementId)
                .orElseThrow(() -> ApplicationException.of(
                        SettlementStatusCode.SETTLEMENT_NOT_FOUND
                ));
        if (!settlement.isCreatedBy(actorId)) {
            throw ApplicationException.of(SettlementStatusCode.SETTLEMENT_ACCESS_DENIED);
        }
        if (settlement.status() != SettlementStatus.REQUESTED
                && settlement.status() != SettlementStatus.AWAITING_DEPOSIT) {
            throw ApplicationException.of(
                    SettlementStatusCode.SETTLEMENT_SHARE_LINK_NOT_AVAILABLE
            );
        }

        SettlementShareLinkStorePort.StoredSettlementShareLink stored = shareLinkStorePort
                .findBySettlementId(settlementId)
                .orElseThrow(() -> ApplicationException.of(
                        SettlementStatusCode.SETTLEMENT_LINK_NOT_FOUND
                ));
        SettlementLink link = stored.link();
        if (link.status() != SettlementLinkStatus.ACTIVE) {
            throw ApplicationException.of(
                    SettlementStatusCode.SETTLEMENT_SHARE_LINK_NOT_AVAILABLE
            );
        }

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        if (stored.rawToken() != null && link.isUsableAt(now)) {
            if (!link.tokenHash().equals(linkTokenPort.hash(stored.rawToken()))) {
                throw ApplicationException.of(SettlementStatusCode.SETTLEMENT_LINK_NOT_FOUND);
            }
            return new SettlementShareLinkResult(
                    linkTokenPort.url(stored.rawToken()),
                    link.expiresAt()
            );
        }

        SettlementLinkTokenPort.IssuedSettlementLinkToken issued = linkTokenPort.issue(now);
        SettlementLink reissued = link.reissue(
                issued.tokenHash(),
                issued.expiresAt(),
                now
        );
        shareLinkStorePort.replace(reissued, issued.rawToken());
        return new SettlementShareLinkResult(issued.url(), issued.expiresAt());
    }
}
