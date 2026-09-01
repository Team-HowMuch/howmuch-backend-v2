package com.amateur.howmuchbackend.application.settlement.service;

import com.amateur.howmuchbackend.application.account.catalog.BankCatalog;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementTransferInstructionResult;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementReceivingAccountSnapshotPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementSharePort;
import com.amateur.howmuchbackend.application.port.out.settlement.SettlementTransferLinkPort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.settlement.Settlement;
import com.amateur.howmuchbackend.domain.settlement.SettlementReceivingAccountSnapshot;
import com.amateur.howmuchbackend.domain.settlement.SettlementShare;
import com.amateur.howmuchbackend.domain.settlement.SettlementStatus;
import com.amateur.howmuchbackend.domain.settlement.status.SettlementStatusCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 참여자가 정해진 뒤의 송금 안내 조회.
 *
 * <p>링크 경로와 회원 경로가 참여자를 찾는 방법만 다르고 그 뒤는 같아서 여기로 모았다.
 *
 * <pre>
 * UUID participantId = ...;  // 링크는 참여 문맥에서, 회원은 로그인 정보에서
 * reader.read(settlementId, participantId);
 * </pre>
 */
@Component
@RequiredArgsConstructor
public class SettlementTransferInstructionReader {
    private final LoadSettlementPort loadSettlementPort;
    private final LoadSettlementSharePort loadSettlementSharePort;
    private final LoadSettlementReceivingAccountSnapshotPort loadSnapshotPort;
    private final List<SettlementTransferLinkPort> transferLinkPorts;
    private final BankCatalog bankCatalog;

    /**
     * 품목 정산은 참여자가 제출할 때마다 그 사람 Share가 생기므로, 전원이 끝나기 전에도
     * 이미 제출한 사람은 송금할 수 있다. 실질 조건은 상태가 아니라 내 Share의 존재다.
     *
     * <p>SUSPENDED는 안 채워진 품목이 남아 생성자가 손봐야 한다는 뜻이지, 이미 정해진
     * 내 몫이 무효라는 뜻이 아니다. 그래서 여기서도 보낼 수 있어야 한다.
     */
    private static final Set<SettlementStatus> PAYABLE_STATUSES = EnumSet.of(
            SettlementStatus.REQUESTED,
            SettlementStatus.AWAITING_DEPOSIT,
            SettlementStatus.SUSPENDED
    );

    public SettlementTransferInstructionResult read(UUID settlementId, UUID participantId) {
        Settlement settlement = loadSettlementPort.findById(settlementId)
                .filter(candidate -> PAYABLE_STATUSES.contains(candidate.status()))
                .orElseThrow(() -> ApplicationException.of(
                        SettlementStatusCode.SETTLEMENT_TRANSFER_INSTRUCTION_NOT_FOUND
                ));
        SettlementShare share = loadSettlementSharePort
                .findBySettlementIdAndPayerParticipantId(settlement.id(), participantId)
                .orElseThrow(() -> ApplicationException.of(
                        SettlementStatusCode.SETTLEMENT_TRANSFER_INSTRUCTION_NOT_FOUND
                ));
        SettlementReceivingAccountSnapshot snapshot = loadSnapshotPort
                .findBySettlementId(settlement.id())
                .orElseThrow(() -> ApplicationException.of(
                        SettlementStatusCode.SETTLEMENT_TRANSFER_INSTRUCTION_NOT_FOUND
                ));
        List<SettlementTransferInstructionResult.TransferLink> transferLinks = transferLinkPorts.stream()
                .map(port -> port.create(snapshot.organization(), snapshot.accountNumber(), share.amount()))
                .flatMap(Optional::stream)
                .map(generatedLink -> new SettlementTransferInstructionResult.TransferLink(
                        generatedLink.provider(),
                        generatedLink.url()
                ))
                .toList();
        if (transferLinks.isEmpty()) {
            throw ApplicationException.of(SettlementStatusCode.SETTLEMENT_TRANSFER_LINK_NOT_SUPPORTED);
        }
        return new SettlementTransferInstructionResult(
                settlement.id(),
                participantId,
                share.id(),
                share.amount(),
                snapshot.accountHolder(),
                snapshot.organization(),
                // 코드만으로는 화면에 은행을 못 보여준다. 이름을 못 찾으면 코드로 대신한다.
                bankCatalog.findBankName(snapshot.organization())
                        .orElse(snapshot.organization()),
                snapshot.accountNumber(),
                transferLinks
        );
    }
}
