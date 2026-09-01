package com.amateur.howmuchbackend.application.settlement.service;

import com.amateur.howmuchbackend.application.auth.model.CurrentActor;
import com.amateur.howmuchbackend.application.auth.policy.IdentityPolicy;
import com.amateur.howmuchbackend.application.group.model.GroupMemberRoster;
import com.amateur.howmuchbackend.application.port.in.settlement.AddSettlementParticipantUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.GetSettlementGroupParticipantCandidatesUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.GetSettlementParticipantAvatarUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.GetSettlementParticipantCandidateAvatarUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.GetSettlementParticipantsUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.MatchSettlementContactsUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.RemoveSettlementParticipantUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.RenameSettlementParticipantUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.SearchSettlementParticipantUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.command.AddSettlementParticipantCommand;
import com.amateur.howmuchbackend.application.port.in.settlement.command.MatchSettlementContactsCommand;
import com.amateur.howmuchbackend.application.port.in.settlement.command.SearchSettlementParticipantCommand;
import com.amateur.howmuchbackend.application.port.in.settlement.command.SettlementContactCommand;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementContactCandidateResult;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementParticipantCandidateResult;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementParticipantListItemResult;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementParticipantResult;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.application.port.out.group.LoadGroupMembersPort;
import com.amateur.howmuchbackend.application.port.out.settlement.DeleteSettlementParticipantPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementParticipantPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementPort;
import com.amateur.howmuchbackend.application.port.out.settlement.SaveSettlementParticipantPort;
import com.amateur.howmuchbackend.application.port.out.settlement.SaveSettlementPort;
import com.amateur.howmuchbackend.application.port.out.settlement.TrackSettlementContactMatchUsagePort;
import com.amateur.howmuchbackend.application.port.out.user.LoadUserPort;
import com.amateur.howmuchbackend.application.port.out.user.MatchContactUsersPort;
import com.amateur.howmuchbackend.application.port.out.user.ProfileImageStoragePort;
import com.amateur.howmuchbackend.application.port.out.user.SearchUserPort;
import com.amateur.howmuchbackend.application.settlement.policy.SettlementParticipantModificationPolicy;
import com.amateur.howmuchbackend.application.settlement.policy.SettlementContactMatchQuotaPolicy;
import com.amateur.howmuchbackend.application.settlement.policy.SettlementContactMatchUsage;
import com.amateur.howmuchbackend.application.user.model.ProfileImage;
import com.amateur.howmuchbackend.application.user.policy.PublicIdPolicy;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.settlement.Settlement;
import com.amateur.howmuchbackend.domain.settlement.SettlementParticipant;
import com.amateur.howmuchbackend.domain.settlement.SettlementParticipantSource;
import com.amateur.howmuchbackend.domain.settlement.status.SettlementStatusCode;
import com.amateur.howmuchbackend.domain.user.User;
import com.amateur.howmuchbackend.domain.user.status.UserStatusCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class SettlementParticipantService implements
        AddSettlementParticipantUseCase,
        RemoveSettlementParticipantUseCase,
        RenameSettlementParticipantUseCase,
        GetSettlementParticipantsUseCase,
        GetSettlementParticipantAvatarUseCase,
        SearchSettlementParticipantUseCase,
        GetSettlementParticipantCandidateAvatarUseCase,
        MatchSettlementContactsUseCase,
        GetSettlementGroupParticipantCandidatesUseCase {
    private static final int MAX_PUBLIC_ID_CANDIDATES = 20;
    private static final int MAX_CONTACTS_PER_REQUEST = 500;
    private static final int MAX_CONTACT_ID_LENGTH = 100;
    private static final int MAX_CONTACT_DISPLAY_NAME_LENGTH = 100;
    private static final int MAX_CONTACT_PHONE_NUMBER_LENGTH = 20;

    private final CurrentActorPort currentActorPort;
    private final LoadGroupMembersPort loadGroupMembersPort;
    private final LoadSettlementPort loadSettlementPort;
    private final LoadSettlementParticipantPort loadSettlementParticipantPort;
    private final SaveSettlementParticipantPort saveSettlementParticipantPort;
    private final DeleteSettlementParticipantPort deleteSettlementParticipantPort;
    private final LoadUserPort loadUserPort;
    private final SearchUserPort searchUserPort;
    private final MatchContactUsersPort matchContactUsersPort;
    private final TrackSettlementContactMatchUsagePort trackSettlementContactMatchUsagePort;
    private final ProfileImageStoragePort profileImageStoragePort;
    private final PublicIdPolicy publicIdPolicy;
    private final IdentityPolicy identityPolicy;
    private final SettlementParticipantModificationPolicy modificationPolicy;
    private final SettlementContactMatchQuotaPolicy contactMatchQuotaPolicy;
    private final SaveSettlementPort saveSettlementPort;
    private final SettlementAllocationDraftInvalidator allocationDraftInvalidator;

    @Override
    @Transactional
    public SettlementParticipantResult add(
            UUID settlementId,
        AddSettlementParticipantCommand command
    ) {
        Settlement settlement = loadOwnedEditableSettlement(settlementId);
        ResolvedParticipant resolved = resolveParticipant(command, settlement.creatorId());
        int displayOrder = loadSettlementParticipantPort.nextDisplayOrder(settlement.id());
        SettlementParticipant participant = resolved.create(
                settlement.id(),
                displayOrder,
                LocalDateTime.now(ZoneOffset.UTC)
        );
        modificationPolicy.validateAddition(participantAlreadyExists(settlement.id(), resolved));
        SettlementParticipant saved = saveSettlementParticipantPort.save(participant);
        invalidateAllocation(settlement);
        return SettlementParticipantResult.of(saved, resolved.publicId());
    }

    @Override
    @Transactional
    public SettlementParticipantResult rename(
            UUID settlementId,
            UUID participantId,
            String displayName
    ) {
        Settlement settlement = loadOwnedEditableSettlement(settlementId);
        SettlementParticipant participant = loadSettlementParticipantPort
                .findBySettlementIdAndId(settlement.id(), participantId)
                .orElseThrow(() -> ApplicationException.of(
                        SettlementStatusCode.SETTLEMENT_PARTICIPANT_NOT_FOUND
                ));
        modificationPolicy.validateRename(settlement, participant);
        SettlementParticipant renamed = saveSettlementParticipantPort.update(
                participant.rename(displayName, LocalDateTime.now(ZoneOffset.UTC))
        );
        return SettlementParticipantResult.of(renamed, resolvePublicId(renamed));
    }

    @Override
    @Transactional
    public void remove(UUID settlementId, UUID participantId) {
        Settlement settlement = loadOwnedEditableSettlement(settlementId);
        SettlementParticipant participant = loadSettlementParticipantPort
                .findBySettlementIdAndId(settlement.id(), participantId)
                .orElseThrow(() -> ApplicationException.of(
                        SettlementStatusCode.SETTLEMENT_PARTICIPANT_NOT_FOUND
                ));
        modificationPolicy.validateRemoval(settlement, participant);
        deleteSettlementParticipantPort.delete(participant);
        invalidateAllocation(settlement);
    }

    @Override
    @Transactional(readOnly = true)
    public List<SettlementParticipantListItemResult> get(UUID settlementId) {
        Settlement settlement = loadOwnedSettlement(settlementId);
        List<SettlementParticipant> participants = loadSettlementParticipantPort
                .findAllBySettlementId(settlement.id());
        Map<Long, User> linkedUsers = loadLinkedUsers(participants);
        return participants.stream()
                .map(participant -> SettlementParticipantListItemResult.of(
                        participant,
                        linkedUsers.get(participant.linkedUserId())
                ))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public ProfileImage get(UUID settlementId, UUID participantId) {
        Settlement settlement = loadOwnedSettlement(settlementId);
        SettlementParticipant participant = loadSettlementParticipantPort
                .findBySettlementIdAndId(settlement.id(), participantId)
                .orElseThrow(() -> ApplicationException.of(
                        SettlementStatusCode.SETTLEMENT_PARTICIPANT_NOT_FOUND
                ));
        if (participant.linkedUserId() == null) {
            throw ApplicationException.of(UserStatusCode.PROFILE_IMAGE_NOT_FOUND);
        }
        User linkedUser = loadUserPort.findById(participant.linkedUserId())
                .orElseThrow(() -> ApplicationException.of(UserStatusCode.PROFILE_IMAGE_NOT_FOUND));
        if (linkedUser.avatarFileName() == null) {
            throw ApplicationException.of(UserStatusCode.PROFILE_IMAGE_NOT_FOUND);
        }
        return profileImageStoragePort.load(linkedUser.avatarFileName())
                .orElseThrow(() -> ApplicationException.of(UserStatusCode.PROFILE_IMAGE_NOT_FOUND));
    }

    @Override
    @Transactional(readOnly = true)
    public List<SettlementParticipantCandidateResult> searchByPublicId(
            UUID settlementId,
            SearchSettlementParticipantCommand command
    ) {
        Settlement settlement = loadOwnedSettlement(settlementId);
        settlement.ensureParticipantsEditable();
        String publicIdPrefix = publicIdPolicy.normalizePrefix(
                command == null ? null : command.publicIdPrefix()
        );
        List<SettlementParticipant> participants = loadSettlementParticipantPort
                .findAllBySettlementId(settlement.id());
        Set<Long> excludedUserIds = participants
                .stream()
                .map(SettlementParticipant::linkedUserId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Set<String> excludedPhoneHashes = participants
                .stream()
                .map(SettlementParticipant::phoneHash)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        return searchUserPort.searchActiveByPublicIdPrefix(
                        publicIdPrefix,
                        excludedUserIds,
                        MAX_PUBLIC_ID_CANDIDATES + excludedPhoneHashes.size()
                ).stream()
                .filter(user -> !excludedPhoneHashes.contains(user.identityKey()))
                .limit(MAX_PUBLIC_ID_CANDIDATES)
                .map(SettlementParticipantCandidateResult::from)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public ProfileImage get(UUID settlementId, String publicId) {
        Settlement settlement = loadOwnedSettlement(settlementId);
        settlement.ensureParticipantsEditable();
        String normalizedPublicId = publicIdPolicy.normalize(publicId);
        User candidate = loadUserPort.findByPublicId(normalizedPublicId)
                .filter(User::active)
                .filter(user -> !candidateAlreadyParticipates(settlement.id(), user))
                .orElseThrow(() -> ApplicationException.of(
                        SettlementStatusCode.SETTLEMENT_PARTICIPANT_CANDIDATE_NOT_FOUND
                ));
        if (candidate.avatarFileName() == null) {
            throw ApplicationException.of(UserStatusCode.PROFILE_IMAGE_NOT_FOUND);
        }
        return profileImageStoragePort.load(candidate.avatarFileName())
                .orElseThrow(() -> ApplicationException.of(UserStatusCode.PROFILE_IMAGE_NOT_FOUND));
    }

    @Override
    @Transactional(readOnly = true)
    public List<SettlementContactCandidateResult> match(
            UUID settlementId,
            MatchSettlementContactsCommand command
    ) {
        Settlement settlement = loadOwnedSettlement(settlementId);
        settlement.ensureParticipantsEditable();
        List<NormalizedContact> contacts = normalizeContacts(command);
        SettlementContactMatchUsage usage = trackSettlementContactMatchUsagePort.recordUsage(
                settlement.creatorId(),
                contacts.size(),
                contactMatchQuotaPolicy.requestWindow(),
                contactMatchQuotaPolicy.contactWindow()
        );
        contactMatchQuotaPolicy.validate(usage);
        Map<String, User> matchedUsers = matchContactUsersPort.findActiveByIdentityKeys(
                        contacts.stream().map(NormalizedContact::phoneHash).collect(Collectors.toSet())
                ).stream()
                .collect(Collectors.toMap(User::identityKey, Function.identity()));
        List<SettlementParticipant> participants = loadSettlementParticipantPort
                .findAllBySettlementId(settlement.id());
        Set<Long> participantUserIds = participants.stream()
                .map(SettlementParticipant::linkedUserId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Set<String> participantPhoneHashes = participants.stream()
                .map(SettlementParticipant::phoneHash)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        return contacts.stream()
                .map(contact -> toContactCandidate(
                        contact,
                        matchedUsers.get(contact.phoneHash()),
                        participantUserIds,
                        participantPhoneHashes
                ))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<SettlementParticipantCandidateResult> get(UUID settlementId, Long groupId) {
        Settlement settlement = loadOwnedSettlement(settlementId);
        settlement.ensureParticipantsEditable();
        if (groupId == null || groupId <= 0) {
            throw ApplicationException.of(SettlementStatusCode.INVALID_SETTLEMENT_GROUP_ID);
        }
        GroupMemberRoster roster = loadGroupMembersPort.findActiveRoster(groupId)
                .orElse(null);
        if (roster == null) {
            return List.of();
        }
        ensureGroupMember(roster, settlement.creatorId());

        List<SettlementParticipant> participants = loadSettlementParticipantPort
                .findAllBySettlementId(settlement.id());
        Set<Long> excludedUserIds = participants.stream()
                .map(SettlementParticipant::linkedUserId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Set<String> excludedPhoneHashes = participants.stream()
                .map(SettlementParticipant::phoneHash)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, User> activeUsersById = loadUserPort.findAllByIds(roster.activeUserIds())
                .stream()
                .filter(User::active)
                .collect(Collectors.toMap(User::id, Function.identity()));

        return roster.activeUserIds().stream()
                .map(activeUsersById::get)
                .filter(Objects::nonNull)
                .filter(user -> !excludedUserIds.contains(user.id()))
                .filter(user -> !excludedPhoneHashes.contains(user.identityKey()))
                .map(SettlementParticipantCandidateResult::from)
                .toList();
    }

    private void ensureGroupMember(GroupMemberRoster roster, Long userId) {
        if (!roster.contains(userId)) {
            throw ApplicationException.of(SettlementStatusCode.SETTLEMENT_GROUP_ACCESS_DENIED);
        }
    }

    private List<NormalizedContact> normalizeContacts(MatchSettlementContactsCommand command) {
        if (command == null
                || command.contacts() == null
                || command.contacts().isEmpty()
                || command.contacts().size() > MAX_CONTACTS_PER_REQUEST) {
            throw invalidContactMatchRequest();
        }
        Set<String> contactIds = new HashSet<>();
        Map<String, NormalizedContact> contactsByPhoneHash = new LinkedHashMap<>();
        for (SettlementContactCommand contact : command.contacts()) {
            if (contact == null) {
                throw invalidContactMatchRequest();
            }
            String contactId = normalizeContactText(contact.contactId(), MAX_CONTACT_ID_LENGTH);
            if (!contactIds.add(contactId)) {
                throw invalidContactMatchRequest();
            }
            String displayName = normalizeContactText(
                    contact.displayName(),
                    MAX_CONTACT_DISPLAY_NAME_LENGTH
            );
            String phoneNumber = contact.phoneNumber();
            if (phoneNumber == null || phoneNumber.length() > MAX_CONTACT_PHONE_NUMBER_LENGTH) {
                throw invalidContactMatchRequest();
            }
            String phoneHash = identityPolicy.phoneHash(phoneNumber);
            contactsByPhoneHash.putIfAbsent(
                    phoneHash,
                    new NormalizedContact(contactId, displayName, phoneHash)
            );
        }
        return List.copyOf(contactsByPhoneHash.values());
    }

    private String normalizeContactText(String value, int maxLength) {
        if (value == null) {
            throw invalidContactMatchRequest();
        }
        String normalized = value.strip();
        if (normalized.isEmpty() || normalized.length() > maxLength) {
            throw invalidContactMatchRequest();
        }
        return normalized;
    }

    private SettlementContactCandidateResult toContactCandidate(
            NormalizedContact contact,
            User matchedUser,
            Set<Long> participantUserIds,
            Set<String> participantPhoneHashes
    ) {
        boolean alreadyParticipant = participantPhoneHashes.contains(contact.phoneHash())
                || matchedUser != null && participantUserIds.contains(matchedUser.id());
        return SettlementContactCandidateResult.of(
                contact.contactId(),
                contact.displayName(),
                matchedUser,
                alreadyParticipant
        );
    }

    private ApplicationException invalidContactMatchRequest() {
        return ApplicationException.of(SettlementStatusCode.INVALID_SETTLEMENT_CONTACT_MATCH_REQUEST);
    }

    private boolean candidateAlreadyParticipates(UUID settlementId, User candidate) {
        return loadSettlementParticipantPort.existsBySettlementIdAndLinkedUserId(
                settlementId,
                candidate.id()
        ) || loadSettlementParticipantPort.existsBySettlementIdAndPhoneHash(
                settlementId,
                candidate.identityKey()
        );
    }

    private String resolvePublicId(SettlementParticipant participant) {
        Long linkedUserId = participant.linkedUserId();
        if (linkedUserId == null) {
            return null;
        }
        return loadUserPort.findAllByIds(Set.of(linkedUserId))
                .stream()
                .findFirst()
                .map(User::publicId)
                .orElse(null);
    }

    private Map<Long, User> loadLinkedUsers(List<SettlementParticipant> participants) {
        Set<Long> linkedUserIds = participants.stream()
                .map(SettlementParticipant::linkedUserId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (linkedUserIds.isEmpty()) {
            return Map.of();
        }
        return loadUserPort.findAllByIds(linkedUserIds)
                .stream()
                .collect(Collectors.toMap(User::id, Function.identity()));
    }

    private Settlement loadOwnedSettlement(UUID settlementId) {
        CurrentActor actor = currentActorPort.getCurrentActor();
        Settlement settlement = loadSettlementPort.findById(settlementId)
                .orElseThrow(() -> ApplicationException.of(SettlementStatusCode.SETTLEMENT_NOT_FOUND));
        ensureOwnedBy(settlement, actor.userId());
        return settlement;
    }

    private Settlement loadOwnedEditableSettlement(UUID settlementId) {
        CurrentActor actor = currentActorPort.getCurrentActor();
        Settlement settlement = loadSettlementPort.findByIdForUpdate(settlementId)
                .orElseThrow(() -> ApplicationException.of(SettlementStatusCode.SETTLEMENT_NOT_FOUND));
        ensureOwnedBy(settlement, actor.userId());
        settlement.ensureParticipantsEditable();
        return settlement;
    }

    private void ensureOwnedBy(Settlement settlement, Long userId) {
        if (!settlement.isCreatedBy(userId)) {
            throw ApplicationException.of(SettlementStatusCode.SETTLEMENT_ACCESS_DENIED);
        }
    }

    private void invalidateAllocation(Settlement settlement) {
        Settlement invalidated = allocationDraftInvalidator.invalidate(
                settlement,
                LocalDateTime.now(ZoneOffset.UTC)
        );
        if (invalidated != settlement) {
            saveSettlementPort.update(invalidated);
        }
    }

    private ResolvedParticipant resolveParticipant(
            AddSettlementParticipantCommand command,
            Long creatorId
    ) {
        if (command == null || command.source() == null) {
            throw invalidAddRequest();
        }
        return switch (command.source()) {
            case REGISTERED_USER -> resolveRegisteredUser(command);
            case CONTACT -> resolveContact(command);
            case GROUP_MEMBER -> resolveGroupMember(command, creatorId);
        };
    }

    private ResolvedParticipant resolveRegisteredUser(AddSettlementParticipantCommand command) {
        if (command.groupId() != null
                || command.publicId() == null
                || command.displayName() != null
                || command.phoneNumber() != null) {
            throw invalidAddRequest();
        }
        String publicId = publicIdPolicy.normalize(command.publicId());
        User user = loadUserPort.findByPublicId(publicId)
                .filter(User::active)
                .orElseThrow(() -> ApplicationException.of(
                        SettlementStatusCode.SETTLEMENT_PARTICIPANT_CANDIDATE_NOT_FOUND
                ));
        return new ResolvedParticipant(
                SettlementParticipantSource.REGISTERED_USER,
                user.id(),
                user.identityKey(),
                null,
                user.name(),
                user.publicId()
        );
    }

    private ResolvedParticipant resolveContact(AddSettlementParticipantCommand command) {
        if (command.groupId() != null
                || command.publicId() != null
                || command.displayName() == null) {
            throw invalidAddRequest();
        }
        // 연락처에 없는 사람은 전화번호 없이 이름만으로 추가한다.
        if (command.phoneNumber() == null) {
            return new ResolvedParticipant(
                    SettlementParticipantSource.CONTACT,
                    null,
                    null,
                    null,
                    command.displayName(),
                    null
            );
        }
        String phoneHash = identityPolicy.phoneHash(command.phoneNumber());
        User linkedUser = loadUserPort.findByIdentityKey(phoneHash)
                .filter(User::active)
                .orElse(null);
        return new ResolvedParticipant(
                SettlementParticipantSource.CONTACT,
                linkedUser == null ? null : linkedUser.id(),
                phoneHash,
                null,
                command.displayName(),
                linkedUser == null ? null : linkedUser.publicId()
        );
    }

    private ResolvedParticipant resolveGroupMember(
            AddSettlementParticipantCommand command,
            Long creatorId
    ) {
        if (command.groupId() == null
                || command.groupId() <= 0
                || command.publicId() == null
                || command.displayName() != null
                || command.phoneNumber() != null) {
            throw invalidAddRequest();
        }
        GroupMemberRoster roster = loadGroupMembersPort.findActiveRoster(command.groupId())
                .orElseThrow(() -> ApplicationException.of(
                        SettlementStatusCode.SETTLEMENT_PARTICIPANT_CANDIDATE_NOT_FOUND
                ));
        ensureGroupMember(roster, creatorId);
        String publicId = publicIdPolicy.normalize(command.publicId());
        User user = loadUserPort.findByPublicId(publicId)
                .filter(User::active)
                .filter(candidate -> roster.contains(candidate.id()))
                .orElseThrow(() -> ApplicationException.of(
                        SettlementStatusCode.SETTLEMENT_PARTICIPANT_CANDIDATE_NOT_FOUND
                ));
        return new ResolvedParticipant(
                SettlementParticipantSource.GROUP_MEMBER,
                user.id(),
                user.identityKey(),
                command.groupId(),
                user.name(),
                user.publicId()
        );
    }

    private ApplicationException invalidAddRequest() {
        return ApplicationException.of(SettlementStatusCode.INVALID_SETTLEMENT_PARTICIPANT_ADD_REQUEST);
    }

    private boolean participantAlreadyExists(UUID settlementId, ResolvedParticipant participant) {
        boolean linkedUserExists = participant.linkedUserId() != null
                && loadSettlementParticipantPort.existsBySettlementIdAndLinkedUserId(
                        settlementId,
                        participant.linkedUserId()
                );
        boolean phoneHashExists = participant.phoneHash() != null
                && loadSettlementParticipantPort.existsBySettlementIdAndPhoneHash(
                        settlementId,
                        participant.phoneHash()
                );
        return linkedUserExists || phoneHashExists;
    }

    private record ResolvedParticipant(
            SettlementParticipantSource source,
            Long linkedUserId,
            String phoneHash,
            Long sourceGroupId,
            String displayName,
            String publicId
    ) {
        private SettlementParticipant create(
                UUID settlementId,
                int displayOrder,
                LocalDateTime now
        ) {
            return switch (source) {
                case REGISTERED_USER -> SettlementParticipant.registeredUser(
                        settlementId,
                        linkedUserId,
                        displayName,
                        displayOrder,
                        now
                );
                case CONTACT -> linkedUserId == null
                        ? SettlementParticipant.unlinkedContact(
                                settlementId,
                                phoneHash,
                                displayName,
                                displayOrder,
                                now
                        )
                        : SettlementParticipant.linkedContact(
                                settlementId,
                                linkedUserId,
                                displayName,
                                displayOrder,
                                now
                        );
                case GROUP_MEMBER -> SettlementParticipant.groupMember(
                        settlementId,
                        sourceGroupId,
                        linkedUserId,
                        displayName,
                        displayOrder,
                        now
                );
            };
        }

    }

    private record NormalizedContact(
            String contactId,
            String displayName,
            String phoneHash
    ) {
    }
}
