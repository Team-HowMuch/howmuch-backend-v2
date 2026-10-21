package com.amateur.howmuchbackend.application.settlement.service;

import com.amateur.howmuchbackend.application.auth.model.CurrentActor;
import com.amateur.howmuchbackend.application.image.ImageCacheVersion;
import com.amateur.howmuchbackend.application.port.in.settlement.GetSettlementLinkParticipantAvatarUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.ResolveSettlementLinkUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.SelectSettlementLinkParticipantUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementLinkEntryMode;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementLinkEntryResult;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementLinkParticipantResult;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementLinkParticipantSelectionResult;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementRequestPhase;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.application.port.out.settlement.IssueSettlementParticipantContextPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementExpensePort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementLinkPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementParticipantPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementSharePort;
import com.amateur.howmuchbackend.application.port.out.settlement.SettlementLinkTokenPort;
import com.amateur.howmuchbackend.application.port.out.user.LoadUserPort;
import com.amateur.howmuchbackend.application.port.out.user.ProfileImageStoragePort;
import com.amateur.howmuchbackend.application.user.model.ProfileImage;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.settlement.Settlement;
import com.amateur.howmuchbackend.domain.settlement.SettlementAllocationType;
import com.amateur.howmuchbackend.domain.settlement.SettlementLink;
import com.amateur.howmuchbackend.domain.settlement.SettlementParticipant;
import com.amateur.howmuchbackend.domain.settlement.SettlementParticipantContext;
import com.amateur.howmuchbackend.domain.settlement.SettlementShare;
import com.amateur.howmuchbackend.domain.settlement.SettlementStatus;
import com.amateur.howmuchbackend.domain.settlement.status.SettlementStatusCode;
import com.amateur.howmuchbackend.domain.user.User;
import com.amateur.howmuchbackend.domain.user.status.UserStatusCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class SettlementLinkEntryService implements
        ResolveSettlementLinkUseCase,
        SelectSettlementLinkParticipantUseCase,
        GetSettlementLinkParticipantAvatarUseCase {
    /**
     * 링크로 들어올 수 있는 정산 상태. 몫을 고르는 REQUESTED 이후에도 계속 열어둔다.
     * 송금 화면을 닫고 은행 앱을 켠 뒤 계좌번호를 다시 보러 오는 동선이 가장 흔하다.
     *
     * <p>SUSPENDED는 안 채워진 품목이 남았다는 뜻이지 이미 정해진 몫이 무효라는 뜻이 아니라
     * {@code SettlementTransferInstructionReader}의 송금 가능 상태와 기준을 맞춘다.
     * COMPLETED는 계좌번호가 담긴 링크를 정산이 끝난 뒤까지 열어두지 않으려고 제외한다.
     */
    private static final Set<SettlementStatus> ENTERABLE_STATUSES = EnumSet.of(
            SettlementStatus.REQUESTED,
            SettlementStatus.AWAITING_DEPOSIT,
            SettlementStatus.SUSPENDED
    );

    private final SettlementLinkTokenPort linkTokenPort;
    private final LoadSettlementLinkPort loadSettlementLinkPort;
    private final LoadSettlementPort loadSettlementPort;
    private final LoadSettlementParticipantPort loadSettlementParticipantPort;
    private final LoadSettlementSharePort loadSettlementSharePort;
    private final CurrentActorPort currentActorPort;
    private final LoadSettlementExpensePort loadSettlementExpensePort;
    private final IssueSettlementParticipantContextPort issueParticipantContextPort;
    private final LoadUserPort loadUserPort;
    private final ProfileImageStoragePort profileImageStoragePort;

    @Override
    public SettlementLinkEntryResult resolve(String rawToken) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        LinkAccess access = loadAccess(rawToken, now);
        List<SettlementParticipant> participants = loadSettlementParticipantPort
                .findAllBySettlementId(access.settlement().id());
        Map<UUID, Long> amountByParticipantId = loadParticipantAmounts(access.settlement());
        SettlementPaymentInfo payment = SettlementPaymentInfo.resolve(
                loadSettlementExpensePort.findAllBySettlementId(access.settlement().id())
        );
        SettlementParticipant matchedParticipant = currentActorPort.findCurrentActor()
                .map(CurrentActor::userId)
                .flatMap(userId -> participants.stream()
                        .filter(participant -> participant.isLinkedTo(userId))
                        .findFirst())
                .orElse(null);

        if (matchedParticipant != null) {
            SettlementLinkParticipantResult selectedParticipant = toResult(
                    access.settlement(),
                    matchedParticipant,
                    amountByParticipantId,
                    loadAvatarVersions(List.of(matchedParticipant))
            );
            SettlementParticipantContext context = issueContext(access, matchedParticipant, now);
            return new SettlementLinkEntryResult(
                    access.settlement().id(),
                    access.settlement().title(),
                    payment.storeName(),
                    payment.paidAt(),
                    requestedAt(access),
                    access.settlement().totalAmount(),
                    participants.size(),
                    access.phase(),
                    SettlementLinkEntryMode.APP_DIRECT,
                    selectedParticipant,
                    List.of(),
                    context.token(),
                    context.expiresAt()
            );
        }

        Map<Long, String> avatarVersions = loadAvatarVersions(participants);
        return new SettlementLinkEntryResult(
                access.settlement().id(),
                access.settlement().title(),
                payment.storeName(),
                payment.paidAt(),
                requestedAt(access),
                access.settlement().totalAmount(),
                participants.size(),
                access.phase(),
                SettlementLinkEntryMode.BROWSER_SELECTION,
                null,
                participants.stream()
                        .map(participant -> toResult(
                                access.settlement(),
                                participant,
                                amountByParticipantId,
                                avatarVersions
                        ))
                        .toList(),
                null,
                null
        );
    }

    @Override
    public SettlementLinkParticipantSelectionResult select(
            String rawToken,
            UUID participantId
    ) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        LinkAccess access = loadAccess(rawToken, now);
        SettlementParticipant participant = loadSettlementParticipantPort
                .findBySettlementIdAndId(access.settlement().id(), participantId)
                .orElseThrow(() -> ApplicationException.of(
                        SettlementStatusCode.SETTLEMENT_PARTICIPANT_NOT_FOUND
                ));
        Map<UUID, Long> amountByParticipantId = loadParticipantAmounts(access.settlement());
        SettlementLinkParticipantResult participantResult = toResult(
                access.settlement(),
                participant,
                amountByParticipantId,
                loadAvatarVersions(List.of(participant))
        );
        SettlementParticipantContext context = issueContext(access, participant, now);
        return new SettlementLinkParticipantSelectionResult(
                access.settlement().id(),
                participantResult,
                access.phase(),
                context.token(),
                context.expiresAt()
        );
    }

    @Override
    public ProfileImage get(String rawToken, UUID participantId) {
        LinkAccess access = loadAccess(rawToken, LocalDateTime.now(ZoneOffset.UTC));
        SettlementParticipant participant = loadSettlementParticipantPort
                .findBySettlementIdAndId(access.settlement().id(), participantId)
                .orElseThrow(() -> ApplicationException.of(
                        SettlementStatusCode.SETTLEMENT_PARTICIPANT_NOT_FOUND
                ));
        if (participant.linkedUserId() == null) {
            throw ApplicationException.of(UserStatusCode.PROFILE_IMAGE_NOT_FOUND);
        }
        User user = loadUserPort.findById(participant.linkedUserId())
                .filter(User::active)
                .filter(found -> found.avatarFileName() != null)
                .orElseThrow(() -> ApplicationException.of(UserStatusCode.PROFILE_IMAGE_NOT_FOUND));
        return profileImageStoragePort.load(user.avatarFileName())
                .orElseThrow(() -> ApplicationException.of(UserStatusCode.PROFILE_IMAGE_NOT_FOUND));
    }

    private Map<Long, String> loadAvatarVersions(List<SettlementParticipant> participants) {
        List<Long> userIds = participants.stream()
                .map(SettlementParticipant::linkedUserId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (userIds.isEmpty()) {
            return Map.of();
        }
        return loadUserPort.findAllByIds(userIds).stream()
                .filter(User::active)
                .filter(user -> user.avatarFileName() != null)
                .collect(Collectors.toUnmodifiableMap(
                        User::id,
                        user -> ImageCacheVersion.fromNullableFileName(user.avatarFileName())
                ));
    }

    private LinkAccess loadAccess(String rawToken, LocalDateTime now) {
        String tokenHash = linkTokenPort.hash(rawToken);
        SettlementLink link = loadSettlementLinkPort.findByTokenHash(tokenHash)
                .orElseThrow(() -> ApplicationException.of(
                        SettlementStatusCode.SETTLEMENT_LINK_NOT_FOUND
                ));
        if (!link.isUsableAt(now)) {
            throw ApplicationException.of(SettlementStatusCode.SETTLEMENT_LINK_NOT_FOUND);
        }
        Settlement settlement = loadSettlementPort.findById(link.settlementId())
                .orElseThrow(() -> ApplicationException.of(
                        SettlementStatusCode.SETTLEMENT_LINK_NOT_FOUND
                ));
        return new LinkAccess(link, settlement, resolvePhase(settlement));
    }

    private LocalDateTime requestedAt(LinkAccess access) {
        return access.link().createdAt() != null
                ? access.link().createdAt()
                : access.settlement().createdAt();
    }

    private SettlementRequestPhase resolvePhase(Settlement settlement) {
        // 품목 정산은 REQUESTED에서만 몫을 고칠 수 있다. 그 외에는 전부 송금 안내 단계다.
        if (settlement.allocationType() == SettlementAllocationType.ITEM_ALLOCATION
                && settlement.status() == SettlementStatus.REQUESTED) {
            return SettlementRequestPhase.ITEM_SELECTION;
        }
        if (ENTERABLE_STATUSES.contains(settlement.status())) {
            return SettlementRequestPhase.TRANSFER_READY;
        }
        throw ApplicationException.of(SettlementStatusCode.SETTLEMENT_LINK_NOT_FOUND);
    }

    private SettlementParticipantContext issueContext(
            LinkAccess access,
            SettlementParticipant participant,
            LocalDateTime now
    ) {
        return issueParticipantContextPort.issue(
                access.link().tokenHash(),
                access.settlement().id(),
                participant.id(),
                now
        );
    }

    private Map<UUID, Long> loadParticipantAmounts(Settlement settlement) {
        if (settlement.allocationType() != SettlementAllocationType.DUTCH_PAY) {
            return Map.of();
        }
        return loadSettlementSharePort.findAllBySettlementId(settlement.id()).stream()
                .collect(Collectors.toUnmodifiableMap(
                        SettlementShare::payerParticipantId,
                        SettlementShare::amount
                ));
    }

    private SettlementLinkParticipantResult toResult(
            Settlement settlement,
            SettlementParticipant participant,
            Map<UUID, Long> amountByParticipantId,
            Map<Long, String> avatarVersions
    ) {
        Long amount = settlement.allocationType() == SettlementAllocationType.DUTCH_PAY
                ? amountByParticipantId.getOrDefault(participant.id(),
                        settlement.status() == SettlementStatus.AWAITING_DEPOSIT ? 0L : null)
                : null;
        return new SettlementLinkParticipantResult(
                participant.id(),
                participant.displayName(),
                participant.isLinkedTo(settlement.creatorId()),
                amount,
                participant.linkedUserId() == null ? null : avatarVersions.get(participant.linkedUserId())
        );
    }

    private record LinkAccess(
            SettlementLink link,
            Settlement settlement,
            SettlementRequestPhase phase
    ) {
    }
}
