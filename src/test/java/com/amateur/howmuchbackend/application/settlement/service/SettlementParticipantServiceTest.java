package com.amateur.howmuchbackend.application.settlement.service;

import com.amateur.howmuchbackend.application.auth.model.CurrentActor;
import com.amateur.howmuchbackend.application.auth.policy.IdentityPolicy;
import com.amateur.howmuchbackend.application.group.model.GroupMemberRoster;
import com.amateur.howmuchbackend.application.port.in.settlement.command.AddSettlementParticipantCommand;
import com.amateur.howmuchbackend.application.port.in.settlement.command.MatchSettlementContactsCommand;
import com.amateur.howmuchbackend.application.port.in.settlement.command.SearchSettlementParticipantCommand;
import com.amateur.howmuchbackend.application.port.in.settlement.command.SettlementContactCommand;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.application.port.out.group.LoadGroupMembersPort;
import com.amateur.howmuchbackend.application.port.out.settlement.DeleteSettlementParticipantPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementParticipantPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementPort;
import com.amateur.howmuchbackend.application.port.out.settlement.SaveSettlementParticipantPort;
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
import com.amateur.howmuchbackend.common.exception.status.CommonStatusCode;
import com.amateur.howmuchbackend.domain.settlement.Settlement;
import com.amateur.howmuchbackend.domain.settlement.SettlementParticipant;
import com.amateur.howmuchbackend.domain.settlement.SettlementParticipantSource;
import com.amateur.howmuchbackend.domain.settlement.SettlementStatus;
import com.amateur.howmuchbackend.domain.settlement.status.SettlementStatusCode;
import com.amateur.howmuchbackend.domain.user.User;
import com.amateur.howmuchbackend.domain.user.UserRole;
import com.amateur.howmuchbackend.domain.auth.status.AuthStatusCode;
import com.amateur.howmuchbackend.domain.user.status.UserStatusCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.time.Duration;
import java.util.List;
import java.util.ArrayList;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("정산 참여자 조회 Application 흐름")
class SettlementParticipantServiceTest {
    private static final Long CREATOR_ID = 1L;
    private static final Long LINKED_USER_ID = 2L;
    private static final Long OTHER_USER_ID = 3L;
    private static final UUID SETTLEMENT_ID = UUID.fromString("44c9323d-6a65-4a93-aeaa-ef8f24341c8a");
    private static final UUID CREATOR_PARTICIPANT_ID =
            UUID.fromString("09ac164a-35d6-4549-bff4-b7b4c375d2aa");
    private static final UUID LINKED_PARTICIPANT_ID =
            UUID.fromString("bbcc9e4e-70df-4a6c-a0e6-4f319de1f89a");
    private static final UUID CONTACT_PARTICIPANT_ID =
            UUID.fromString("f2878f10-77cc-4e85-94a5-343b414f69d7");
    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 8, 22, 12, 0);

    @Mock
    private CurrentActorPort currentActorPort;

    @Mock
    private LoadGroupMembersPort loadGroupMembersPort;

    @Mock
    private LoadSettlementPort loadSettlementPort;

    @Mock
    private LoadSettlementParticipantPort loadSettlementParticipantPort;

    @Mock
    private SaveSettlementParticipantPort saveSettlementParticipantPort;

    @Mock
    private DeleteSettlementParticipantPort deleteSettlementParticipantPort;

    @Mock
    private LoadUserPort loadUserPort;

    @Mock
    private SearchUserPort searchUserPort;

    @Mock
    private MatchContactUsersPort matchContactUsersPort;

    @Mock
    private TrackSettlementContactMatchUsagePort trackSettlementContactMatchUsagePort;

    @Mock
    private ProfileImageStoragePort profileImageStoragePort;

    private SettlementParticipantService settlementParticipantService;
    private IdentityPolicy identityPolicy;

    @BeforeEach
    void setUp() {
        identityPolicy = new IdentityPolicy("test-identity-secret");
        lenient().when(trackSettlementContactMatchUsagePort.recordUsage(
                any(),
                anyInt(),
                any(),
                any()
        )).thenAnswer(invocation -> new SettlementContactMatchUsage(
                1,
                invocation.getArgument(1, Integer.class)
        ));
        settlementParticipantService = new SettlementParticipantService(
                currentActorPort,
                loadGroupMembersPort,
                loadSettlementPort,
                loadSettlementParticipantPort,
                saveSettlementParticipantPort,
                deleteSettlementParticipantPort,
                loadUserPort,
                searchUserPort,
                matchContactUsersPort,
                trackSettlementContactMatchUsagePort,
                profileImageStoragePort,
                new PublicIdPolicy(),
                identityPolicy,
                new SettlementParticipantModificationPolicy(),
                new SettlementContactMatchQuotaPolicy()
        );
    }

    @Test
    @DisplayName("화면 복원에 필요한 공개 정보만 표시 순서대로 조립한다")
    void getsParticipantListWithPublicUserInformation() {
        List<SettlementParticipant> participants = List.of(
                registeredCreator(),
                linkedContact(),
                unlinkedContact()
        );
        when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(CREATOR_ID));
        when(loadSettlementPort.findById(SETTLEMENT_ID)).thenReturn(Optional.of(settlement()));
        when(loadSettlementParticipantPort.findAllBySettlementId(SETTLEMENT_ID))
                .thenReturn(participants);
        when(loadUserPort.findAllByIds(Set.of(CREATOR_ID, LINKED_USER_ID)))
                .thenReturn(List.of(creator(), linkedUser("linked-avatar.png")));

        var result = settlementParticipantService.get(SETTLEMENT_ID);

        assertThat(result).extracting(item -> item.displayOrder()).containsExactly(0, 1, 2);
        assertThat(result.get(0).participantId()).isEqualTo(CREATOR_PARTICIPANT_ID);
        assertThat(result.get(0).publicId()).isEqualTo("creator.id");
        assertThat(result.get(0).hasAvatar()).isFalse();
        assertThat(result.get(1).source()).isEqualTo(SettlementParticipantSource.CONTACT);
        assertThat(result.get(1).publicId()).isEqualTo("linked.id");
        assertThat(result.get(1).hasAvatar()).isTrue();
        assertThat(result.get(2).publicId()).isNull();
        assertThat(result.get(2).hasAvatar()).isFalse();
        verify(loadUserPort).findAllByIds(Set.of(CREATOR_ID, LINKED_USER_ID));
    }

    @Test
    @DisplayName("생성자만 참여자 목록을 볼 수 있도록 다른 사용자의 조회를 거부한다")
    void rejectsParticipantListForAnotherUser() {
        when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(OTHER_USER_ID));
        when(loadSettlementPort.findById(SETTLEMENT_ID)).thenReturn(Optional.of(settlement()));

        assertThatThrownBy(() -> settlementParticipantService.get(SETTLEMENT_ID))
                .isInstanceOfSatisfying(ApplicationException.class, exception ->
                        assertThat(exception.getStatusCode())
                                .isEqualTo(SettlementStatusCode.SETTLEMENT_ACCESS_DENIED));
        verify(loadSettlementParticipantPort, never()).findAllBySettlementId(SETTLEMENT_ID);
    }

    @Test
    @DisplayName("생성자만 참여자 아바타를 볼 수 있도록 다른 사용자의 조회를 거부한다")
    void rejectsParticipantAvatarForAnotherUser() {
        when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(OTHER_USER_ID));
        when(loadSettlementPort.findById(SETTLEMENT_ID)).thenReturn(Optional.of(settlement()));

        assertThatThrownBy(() -> settlementParticipantService.get(
                SETTLEMENT_ID,
                LINKED_PARTICIPANT_ID
        )).isInstanceOfSatisfying(ApplicationException.class, exception ->
                assertThat(exception.getStatusCode())
                        .isEqualTo(SettlementStatusCode.SETTLEMENT_ACCESS_DENIED));
        verify(loadSettlementParticipantPort, never()).findBySettlementIdAndId(
                SETTLEMENT_ID,
                LINKED_PARTICIPANT_ID
        );
    }

    @Test
    @DisplayName("없는 정산의 참여자 목록은 SETTLEMENT_NOT_FOUND로 거부한다")
    void rejectsParticipantListForMissingSettlement() {
        when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(CREATOR_ID));
        when(loadSettlementPort.findById(SETTLEMENT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> settlementParticipantService.get(SETTLEMENT_ID))
                .isInstanceOfSatisfying(ApplicationException.class, exception ->
                        assertThat(exception.getStatusCode())
                                .isEqualTo(SettlementStatusCode.SETTLEMENT_NOT_FOUND));
        verify(loadSettlementParticipantPort, never()).findAllBySettlementId(SETTLEMENT_ID);
    }

    @Test
    @DisplayName("회원 참여자의 저장된 아바타 바이트와 Content-Type을 반환한다")
    void getsLinkedParticipantAvatar() {
        byte[] content = {(byte) 0x89, 0x50, 0x4E, 0x47};
        ProfileImage image = new ProfileImage("image/png", content);
        when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(CREATOR_ID));
        when(loadSettlementPort.findById(SETTLEMENT_ID)).thenReturn(Optional.of(settlement()));
        when(loadSettlementParticipantPort.findBySettlementIdAndId(
                SETTLEMENT_ID,
                LINKED_PARTICIPANT_ID
        )).thenReturn(Optional.of(linkedContact()));
        when(loadUserPort.findById(LINKED_USER_ID)).thenReturn(Optional.of(linkedUser("linked-avatar.png")));
        when(profileImageStoragePort.load("linked-avatar.png")).thenReturn(Optional.of(image));

        ProfileImage result = settlementParticipantService.get(SETTLEMENT_ID, LINKED_PARTICIPANT_ID);

        assertThat(result.contentType()).isEqualTo("image/png");
        assertThat(result.content()).containsExactly(content);
    }

    @Test
    @DisplayName("정산에 속하지 않은 참여자의 아바타 요청은 PARTICIPANT_NOT_FOUND로 거부한다")
    void rejectsAvatarForMissingParticipant() {
        when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(CREATOR_ID));
        when(loadSettlementPort.findById(SETTLEMENT_ID)).thenReturn(Optional.of(settlement()));
        when(loadSettlementParticipantPort.findBySettlementIdAndId(
                SETTLEMENT_ID,
                LINKED_PARTICIPANT_ID
        )).thenReturn(Optional.empty());

        assertThatThrownBy(() -> settlementParticipantService.get(
                SETTLEMENT_ID,
                LINKED_PARTICIPANT_ID
        )).isInstanceOfSatisfying(ApplicationException.class, exception ->
                assertThat(exception.getStatusCode())
                        .isEqualTo(SettlementStatusCode.SETTLEMENT_PARTICIPANT_NOT_FOUND));
        verifyNoInteractions(loadUserPort);
    }

    @Test
    @DisplayName("아바타 파일이 유실된 경우 PROFILE_IMAGE_NOT_FOUND를 반환한다")
    void rejectsMissingStoredAvatar() {
        when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(CREATOR_ID));
        when(loadSettlementPort.findById(SETTLEMENT_ID)).thenReturn(Optional.of(settlement()));
        when(loadSettlementParticipantPort.findBySettlementIdAndId(
                SETTLEMENT_ID,
                LINKED_PARTICIPANT_ID
        )).thenReturn(Optional.of(linkedContact()));
        when(loadUserPort.findById(LINKED_USER_ID)).thenReturn(Optional.of(linkedUser("missing.png")));
        when(profileImageStoragePort.load("missing.png")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> settlementParticipantService.get(
                SETTLEMENT_ID,
                LINKED_PARTICIPANT_ID
        )).isInstanceOfSatisfying(ApplicationException.class, exception ->
                assertThat(exception.getStatusCode())
                        .isEqualTo(UserStatusCode.PROFILE_IMAGE_NOT_FOUND));
    }

    @Test
    @DisplayName("비회원 연락처는 연결된 회원 정보 없이 PROFILE_IMAGE_NOT_FOUND를 반환한다")
    void rejectsAvatarForUnlinkedContact() {
        when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(CREATOR_ID));
        when(loadSettlementPort.findById(SETTLEMENT_ID)).thenReturn(Optional.of(settlement()));
        when(loadSettlementParticipantPort.findBySettlementIdAndId(
                SETTLEMENT_ID,
                CONTACT_PARTICIPANT_ID
        )).thenReturn(Optional.of(unlinkedContact()));

        assertThatThrownBy(() -> settlementParticipantService.get(
                SETTLEMENT_ID,
                CONTACT_PARTICIPANT_ID
        )).isInstanceOfSatisfying(ApplicationException.class, exception ->
                assertThat(exception.getStatusCode())
                        .isEqualTo(UserStatusCode.PROFILE_IMAGE_NOT_FOUND));
        verifyNoInteractions(loadUserPort);
    }

    @Test
    @DisplayName("공개 ID prefix를 정규화하고 이미 추가된 회원을 제외해 후보 공개 정보만 반환한다")
    void searchesPublicIdCandidates() {
        User firstCandidate = user(OTHER_USER_ID, "sua.alpha", "candidate.png");
        User secondCandidate = user(4L, "sua.beta", null);
        when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(CREATOR_ID));
        when(loadSettlementPort.findById(SETTLEMENT_ID)).thenReturn(Optional.of(draftSettlement()));
        when(loadSettlementParticipantPort.findAllBySettlementId(SETTLEMENT_ID))
                .thenReturn(List.of(registeredCreator(), linkedContact()));
        when(searchUserPort.searchActiveByPublicIdPrefix(
                "sua.",
                Set.of(CREATOR_ID, LINKED_USER_ID),
                20
        )).thenReturn(List.of(firstCandidate, secondCandidate));

        var result = settlementParticipantService.searchByPublicId(
                SETTLEMENT_ID,
                new SearchSettlementParticipantCommand("  SUA.  ")
        );

        assertThat(result).extracting(item -> item.publicId())
                .containsExactly("sua.alpha", "sua.beta");
        assertThat(result).extracting(item -> item.displayName())
                .containsExactly("회원 3", "회원 4");
        assertThat(result).extracting(item -> item.hasAvatar())
                .containsExactly(true, false);
    }

    @Test
    @DisplayName("연락처 추가 후 가입한 회원은 phoneHash가 일치하면 공개 ID 후보에서 제외한다")
    void excludesCandidateMatchedByExistingContactHash() {
        String phoneHash = "a".repeat(64);
        User convertedContact = user(OTHER_USER_ID, phoneHash, "sua.contact", null);
        User availableCandidate = user(4L, "sua.available", null);
        when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(CREATOR_ID));
        when(loadSettlementPort.findById(SETTLEMENT_ID)).thenReturn(Optional.of(draftSettlement()));
        when(loadSettlementParticipantPort.findAllBySettlementId(SETTLEMENT_ID))
                .thenReturn(List.of(registeredCreator(), unlinkedContact()));
        when(searchUserPort.searchActiveByPublicIdPrefix(
                "sua.",
                Set.of(CREATOR_ID),
                21
        )).thenReturn(List.of(convertedContact, availableCandidate));

        var result = settlementParticipantService.searchByPublicId(
                SETTLEMENT_ID,
                new SearchSettlementParticipantCommand("sua.")
        );

        assertThat(result).extracting(item -> item.publicId())
                .containsExactly("sua.available");
    }

    @Test
    @DisplayName("생성자가 아닌 사용자는 회원 후보를 검색할 수 없다")
    void rejectsCandidateSearchForAnotherUser() {
        when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(OTHER_USER_ID));
        when(loadSettlementPort.findById(SETTLEMENT_ID)).thenReturn(Optional.of(draftSettlement()));

        assertThatThrownBy(() -> settlementParticipantService.searchByPublicId(
                SETTLEMENT_ID,
                new SearchSettlementParticipantCommand("sua")
        )).isInstanceOfSatisfying(ApplicationException.class, exception ->
                assertThat(exception.getStatusCode())
                        .isEqualTo(SettlementStatusCode.SETTLEMENT_ACCESS_DENIED));
        verifyNoInteractions(searchUserPort);
    }

    @Test
    @DisplayName("참여자를 변경할 수 없는 정산에서는 회원 후보 검색도 거부한다")
    void rejectsCandidateSearchForRequestedSettlement() {
        when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(CREATOR_ID));
        when(loadSettlementPort.findById(SETTLEMENT_ID)).thenReturn(Optional.of(settlement()));

        assertThatThrownBy(() -> settlementParticipantService.searchByPublicId(
                SETTLEMENT_ID,
                new SearchSettlementParticipantCommand("sua")
        )).isInstanceOfSatisfying(ApplicationException.class, exception ->
                assertThat(exception.getStatusCode())
                        .isEqualTo(SettlementStatusCode.SETTLEMENT_NOT_EDITABLE));
        verifyNoInteractions(searchUserPort);
    }

    @Test
    @DisplayName("유효하지 않은 공개 ID prefix는 사용자 저장소를 조회하기 전에 거부한다")
    void rejectsInvalidCandidateSearchPrefix() {
        when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(CREATOR_ID));
        when(loadSettlementPort.findById(SETTLEMENT_ID)).thenReturn(Optional.of(draftSettlement()));

        assertThatThrownBy(() -> settlementParticipantService.searchByPublicId(
                SETTLEMENT_ID,
                new SearchSettlementParticipantCommand("sua-")
        )).isInstanceOfSatisfying(ApplicationException.class, exception ->
                assertThat(exception.getStatusCode())
                        .isEqualTo(UserStatusCode.INVALID_PUBLIC_ID_SEARCH_PREFIX));
        verifyNoInteractions(searchUserPort);
    }

    @Test
    @DisplayName("미추가 활성 회원 후보의 저장된 아바타를 반환한다")
    void getsParticipantCandidateAvatar() {
        ProfileImage image = new ProfileImage("image/png", new byte[]{1, 2, 3});
        User candidate = user(OTHER_USER_ID, "sua.alpha", "candidate.png");
        when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(CREATOR_ID));
        when(loadSettlementPort.findById(SETTLEMENT_ID)).thenReturn(Optional.of(draftSettlement()));
        when(loadUserPort.findByPublicId("sua.alpha")).thenReturn(Optional.of(candidate));
        when(loadSettlementParticipantPort.existsBySettlementIdAndLinkedUserId(
                SETTLEMENT_ID,
                OTHER_USER_ID
        )).thenReturn(false);
        when(profileImageStoragePort.load("candidate.png")).thenReturn(Optional.of(image));

        ProfileImage result = settlementParticipantService.get(SETTLEMENT_ID, "SUA.ALPHA");

        assertThat(result.contentType()).isEqualTo("image/png");
        assertThat(result.content()).containsExactly(1, 2, 3);
    }

    @Test
    @DisplayName("이미 추가된 회원의 공개 ID는 후보 아바타 경로에서도 숨긴다")
    void rejectsAvatarForExistingParticipantCandidate() {
        User candidate = user(LINKED_USER_ID, "linked.id", "linked-avatar.png");
        when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(CREATOR_ID));
        when(loadSettlementPort.findById(SETTLEMENT_ID)).thenReturn(Optional.of(draftSettlement()));
        when(loadUserPort.findByPublicId("linked.id")).thenReturn(Optional.of(candidate));
        when(loadSettlementParticipantPort.existsBySettlementIdAndLinkedUserId(
                SETTLEMENT_ID,
                LINKED_USER_ID
        )).thenReturn(true);

        assertThatThrownBy(() -> settlementParticipantService.get(SETTLEMENT_ID, "linked.id"))
                .isInstanceOfSatisfying(ApplicationException.class, exception ->
                        assertThat(exception.getStatusCode()).isEqualTo(
                                SettlementStatusCode.SETTLEMENT_PARTICIPANT_CANDIDATE_NOT_FOUND
                        ));
        verifyNoInteractions(profileImageStoragePort);
    }

    @Test
    @DisplayName("연락처 추가 후 가입한 회원의 아바타도 후보 경로에서 숨긴다")
    void rejectsCandidateAvatarMatchedByExistingContactHash() {
        String phoneHash = "a".repeat(64);
        User candidate = user(OTHER_USER_ID, phoneHash, "sua.contact", "candidate.png");
        when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(CREATOR_ID));
        when(loadSettlementPort.findById(SETTLEMENT_ID)).thenReturn(Optional.of(draftSettlement()));
        when(loadUserPort.findByPublicId("sua.contact")).thenReturn(Optional.of(candidate));
        when(loadSettlementParticipantPort.existsBySettlementIdAndLinkedUserId(
                SETTLEMENT_ID,
                OTHER_USER_ID
        )).thenReturn(false);
        when(loadSettlementParticipantPort.existsBySettlementIdAndPhoneHash(
                SETTLEMENT_ID,
                phoneHash
        )).thenReturn(true);

        assertThatThrownBy(() -> settlementParticipantService.get(SETTLEMENT_ID, "sua.contact"))
                .isInstanceOfSatisfying(ApplicationException.class, exception ->
                        assertThat(exception.getStatusCode()).isEqualTo(
                                SettlementStatusCode.SETTLEMENT_PARTICIPANT_CANDIDATE_NOT_FOUND
                        ));
        verifyNoInteractions(profileImageStoragePort);
    }

    @Test
    @DisplayName("연락처를 HMAC으로 일괄 매칭하고 입력 순서와 공개 정보만 반환한다")
    void matchesSettlementContacts() {
        String memberPhone = "010-1111-2222";
        String externalPhone = "010-2222-3333";
        String existingMemberPhone = "010-3333-4444";
        String existingExternalPhone = "010-4444-5555";
        String memberHash = identityPolicy.phoneHash(memberPhone);
        String externalHash = identityPolicy.phoneHash(externalPhone);
        String existingMemberHash = identityPolicy.phoneHash(existingMemberPhone);
        String existingExternalHash = identityPolicy.phoneHash(existingExternalPhone);
        User member = user(OTHER_USER_ID, memberHash, "contact.member", "member.png");
        User existingMember = user(LINKED_USER_ID, existingMemberHash, "linked.id", "linked.png");
        when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(CREATOR_ID));
        when(loadSettlementPort.findById(SETTLEMENT_ID)).thenReturn(Optional.of(draftSettlement()));
        when(matchContactUsersPort.findActiveByIdentityKeys(Set.of(
                memberHash,
                externalHash,
                existingMemberHash,
                existingExternalHash
        ))).thenReturn(List.of(member, existingMember));
        when(loadSettlementParticipantPort.findAllBySettlementId(SETTLEMENT_ID))
                .thenReturn(List.of(
                        registeredCreator(),
                        linkedContact(),
                        unlinkedContact(existingExternalHash)
                ));

        var result = settlementParticipantService.match(
                SETTLEMENT_ID,
                new MatchSettlementContactsCommand(List.of(
                        contact("  first  ", "  주소록 회원  ", memberPhone),
                        contact("second", "비회원", externalPhone),
                        contact("duplicate-phone", "중복 번호", "01011112222"),
                        contact("existing-member", "기존 회원", existingMemberPhone),
                        contact("existing-contact", "기존 연락처", existingExternalPhone)
                ))
        );

        assertThat(result).extracting(item -> item.contactId())
                .containsExactly("first", "second", "existing-member", "existing-contact");
        assertThat(result).extracting(item -> item.contactDisplayName())
                .containsExactly("주소록 회원", "비회원", "기존 회원", "기존 연락처");
        assertThat(result).extracting(item -> item.member())
                .containsExactly(true, false, true, false);
        assertThat(result).extracting(item -> item.alreadyParticipant())
                .containsExactly(false, false, true, true);
        assertThat(result).extracting(item -> item.hasAvatar())
                .containsExactly(true, false, false, false);
        assertThat(result.get(0).publicId()).isEqualTo("contact.member");
        assertThat(result.get(0).memberDisplayName()).isEqualTo("회원 3");
        assertThat(result.get(1).publicId()).isNull();
        verify(trackSettlementContactMatchUsagePort).recordUsage(
                CREATOR_ID,
                4,
                Duration.ofMinutes(1),
                Duration.ofHours(24)
        );
    }

    @Test
    @DisplayName("분당 연락처 매칭 요청 한도를 초과하면 회원 조회를 거부한다")
    void rejectsContactMatchingOverRequestQuota() {
        when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(CREATOR_ID));
        when(loadSettlementPort.findById(SETTLEMENT_ID)).thenReturn(Optional.of(draftSettlement()));
        when(trackSettlementContactMatchUsagePort.recordUsage(
                CREATOR_ID,
                1,
                Duration.ofMinutes(1),
                Duration.ofHours(24)
        )).thenReturn(new SettlementContactMatchUsage(11, 1));

        assertThatThrownBy(() -> settlementParticipantService.match(
                SETTLEMENT_ID,
                new MatchSettlementContactsCommand(List.of(
                        contact("contact", "연락처", "01011112222")
                ))
        )).isInstanceOfSatisfying(ApplicationException.class, exception ->
                assertThat(exception.getStatusCode()).isEqualTo(CommonStatusCode.TOO_MANY_REQUEST));
        verifyNoInteractions(matchContactUsersPort);
    }

    @Test
    @DisplayName("일일 연락처 조회 한도를 초과하면 회원 조회를 거부한다")
    void rejectsContactMatchingOverContactQuota() {
        when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(CREATOR_ID));
        when(loadSettlementPort.findById(SETTLEMENT_ID)).thenReturn(Optional.of(draftSettlement()));
        when(trackSettlementContactMatchUsagePort.recordUsage(
                CREATOR_ID,
                1,
                Duration.ofMinutes(1),
                Duration.ofHours(24)
        )).thenReturn(new SettlementContactMatchUsage(1, 2_001));

        assertThatThrownBy(() -> settlementParticipantService.match(
                SETTLEMENT_ID,
                new MatchSettlementContactsCommand(List.of(
                        contact("contact", "연락처", "01011112222")
                ))
        )).isInstanceOfSatisfying(ApplicationException.class, exception ->
                assertThat(exception.getStatusCode()).isEqualTo(CommonStatusCode.TOO_MANY_REQUEST));
        verifyNoInteractions(matchContactUsersPort);
    }

    @Test
    @DisplayName("같은 연락처 ID가 여러 전화번호를 가리키는 매칭 요청을 거부한다")
    void rejectsDuplicateContactIds() {
        when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(CREATOR_ID));
        when(loadSettlementPort.findById(SETTLEMENT_ID)).thenReturn(Optional.of(draftSettlement()));

        assertThatThrownBy(() -> settlementParticipantService.match(
                SETTLEMENT_ID,
                new MatchSettlementContactsCommand(List.of(
                        contact("same", "첫 번째", "01011112222"),
                        contact(" same ", "두 번째", "01022223333")
                ))
        )).isInstanceOfSatisfying(ApplicationException.class, exception ->
                assertThat(exception.getStatusCode()).isEqualTo(
                        SettlementStatusCode.INVALID_SETTLEMENT_CONTACT_MATCH_REQUEST
                ));
        verifyNoInteractions(matchContactUsersPort);
    }

    @Test
    @DisplayName("잘못된 휴대폰 번호가 포함된 연락처 매칭 요청을 거부한다")
    void rejectsInvalidContactPhoneNumber() {
        when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(CREATOR_ID));
        when(loadSettlementPort.findById(SETTLEMENT_ID)).thenReturn(Optional.of(draftSettlement()));

        assertThatThrownBy(() -> settlementParticipantService.match(
                SETTLEMENT_ID,
                new MatchSettlementContactsCommand(List.of(
                        contact("invalid-phone", "유선 번호", "02-1234-5678")
                ))
        )).isInstanceOfSatisfying(ApplicationException.class, exception ->
                assertThat(exception.getStatusCode()).isEqualTo(AuthStatusCode.INVALID_PHONE_NUMBER));
        verifyNoInteractions(matchContactUsersPort);
    }

    @Test
    @DisplayName("원본 길이 제한을 초과한 휴대폰 번호를 정규화 전에 거부한다")
    void rejectsOversizedRawContactPhoneNumber() {
        when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(CREATOR_ID));
        when(loadSettlementPort.findById(SETTLEMENT_ID)).thenReturn(Optional.of(draftSettlement()));

        assertThatThrownBy(() -> settlementParticipantService.match(
                SETTLEMENT_ID,
                new MatchSettlementContactsCommand(List.of(
                        contact(
                                "oversized-phone",
                                "긴 구분자 번호",
                                "-".repeat(21) + "01011112222"
                        )
                ))
        )).isInstanceOfSatisfying(ApplicationException.class, exception ->
                assertThat(exception.getStatusCode()).isEqualTo(
                        SettlementStatusCode.INVALID_SETTLEMENT_CONTACT_MATCH_REQUEST
                ));
        verifyNoInteractions(matchContactUsersPort);
    }

    @Test
    @DisplayName("한 요청에서 500개를 초과한 연락처 매칭을 거부한다")
    void rejectsTooManyContacts() {
        List<SettlementContactCommand> contacts = new ArrayList<>();
        for (int index = 0; index < 501; index++) {
            contacts.add(contact("contact-" + index, "연락처 " + index, "01011112222"));
        }
        when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(CREATOR_ID));
        when(loadSettlementPort.findById(SETTLEMENT_ID)).thenReturn(Optional.of(draftSettlement()));

        assertThatThrownBy(() -> settlementParticipantService.match(
                SETTLEMENT_ID,
                new MatchSettlementContactsCommand(contacts)
        )).isInstanceOfSatisfying(ApplicationException.class, exception ->
                assertThat(exception.getStatusCode()).isEqualTo(
                        SettlementStatusCode.INVALID_SETTLEMENT_CONTACT_MATCH_REQUEST
                ));
        verifyNoInteractions(matchContactUsersPort);
    }

    @Test
    @DisplayName("생성자가 아닌 사용자는 주소록 연락처를 매칭할 수 없다")
    void rejectsContactMatchingForAnotherUser() {
        when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(OTHER_USER_ID));
        when(loadSettlementPort.findById(SETTLEMENT_ID)).thenReturn(Optional.of(draftSettlement()));

        assertThatThrownBy(() -> settlementParticipantService.match(
                SETTLEMENT_ID,
                new MatchSettlementContactsCommand(List.of(
                        contact("contact", "연락처", "01011112222")
                ))
        )).isInstanceOfSatisfying(ApplicationException.class, exception ->
                assertThat(exception.getStatusCode())
                        .isEqualTo(SettlementStatusCode.SETTLEMENT_ACCESS_DENIED));
        verifyNoInteractions(matchContactUsersPort);
    }

    @Test
    @DisplayName("참여자를 변경할 수 없는 정산에서는 연락처 매칭도 거부한다")
    void rejectsContactMatchingForRequestedSettlement() {
        when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(CREATOR_ID));
        when(loadSettlementPort.findById(SETTLEMENT_ID)).thenReturn(Optional.of(settlement()));

        assertThatThrownBy(() -> settlementParticipantService.match(
                SETTLEMENT_ID,
                new MatchSettlementContactsCommand(List.of(
                        contact("contact", "연락처", "01011112222")
                ))
        )).isInstanceOfSatisfying(ApplicationException.class, exception ->
                assertThat(exception.getStatusCode())
                        .isEqualTo(SettlementStatusCode.SETTLEMENT_NOT_EDITABLE));
        verifyNoInteractions(matchContactUsersPort);
    }

    @Test
    @DisplayName("그룹 구성원 순서를 유지하고 이미 추가된 동일인을 제외해 후보를 반환한다")
    void getsGroupParticipantCandidates() {
        Long groupId = 10L;
        String convertedContactHash = "a".repeat(64);
        User available = user(OTHER_USER_ID, "group.available", "group.png");
        User convertedContact = user(4L, convertedContactHash, "group.converted", null);
        when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(CREATOR_ID));
        when(loadSettlementPort.findById(SETTLEMENT_ID)).thenReturn(Optional.of(draftSettlement()));
        when(loadGroupMembersPort.findActiveRoster(groupId)).thenReturn(Optional.of(
                new GroupMemberRoster(groupId, List.of(CREATOR_ID, OTHER_USER_ID, 4L, LINKED_USER_ID))
        ));
        when(loadSettlementParticipantPort.findAllBySettlementId(SETTLEMENT_ID))
                .thenReturn(List.of(
                        registeredCreator(),
                        linkedContact(),
                        unlinkedContact(convertedContactHash)
                ));
        when(loadUserPort.findAllByIds(List.of(CREATOR_ID, OTHER_USER_ID, 4L, LINKED_USER_ID)))
                .thenReturn(List.of(convertedContact, available, creator(), linkedUser(null)));

        var result = settlementParticipantService.get(SETTLEMENT_ID, groupId);

        assertThat(result).extracting(item -> item.publicId())
                .containsExactly("group.available");
        assertThat(result.getFirst().hasAvatar()).isTrue();
    }

    @Test
    @DisplayName("정산 생성자가 그룹 구성원이 아니면 후보 조회를 거부한다")
    void rejectsGroupCandidatesForNonMemberCreator() {
        Long groupId = 10L;
        when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(CREATOR_ID));
        when(loadSettlementPort.findById(SETTLEMENT_ID)).thenReturn(Optional.of(draftSettlement()));
        when(loadGroupMembersPort.findActiveRoster(groupId)).thenReturn(Optional.of(
                new GroupMemberRoster(groupId, List.of(OTHER_USER_ID))
        ));

        assertThatThrownBy(() -> settlementParticipantService.get(SETTLEMENT_ID, groupId))
                .isInstanceOfSatisfying(ApplicationException.class, exception ->
                        assertThat(exception.getStatusCode()).isEqualTo(
                                SettlementStatusCode.SETTLEMENT_GROUP_ACCESS_DENIED
                        ));
        verifyNoInteractions(loadUserPort);
    }

    @Test
    @DisplayName("연결된 그룹 정보가 없으면 다른 참여자 경로를 위해 빈 후보를 반환한다")
    void returnsEmptyGroupCandidatesWhenRosterIsUnavailable() {
        Long groupId = 10L;
        when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(CREATOR_ID));
        when(loadSettlementPort.findById(SETTLEMENT_ID)).thenReturn(Optional.of(draftSettlement()));
        when(loadGroupMembersPort.findActiveRoster(groupId)).thenReturn(Optional.empty());

        assertThat(settlementParticipantService.get(SETTLEMENT_ID, groupId)).isEmpty();
        verifyNoInteractions(loadUserPort, loadSettlementParticipantPort);
    }

    @Test
    @DisplayName("양수가 아닌 그룹 ID는 그룹 조회 전에 거부한다")
    void rejectsInvalidGroupIdForCandidateLookup() {
        when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(CREATOR_ID));
        when(loadSettlementPort.findById(SETTLEMENT_ID)).thenReturn(Optional.of(draftSettlement()));

        assertThatThrownBy(() -> settlementParticipantService.get(SETTLEMENT_ID, 0L))
                .isInstanceOfSatisfying(ApplicationException.class, exception ->
                        assertThat(exception.getStatusCode()).isEqualTo(
                                SettlementStatusCode.INVALID_SETTLEMENT_GROUP_ID
                        ));
        verifyNoInteractions(loadGroupMembersPort);
    }

    @Test
    @DisplayName("그룹 소속을 다시 확인한 활성 회원을 그룹 참여자로 저장한다")
    void addsGroupMemberParticipant() {
        Long groupId = 10L;
        User groupMember = user(OTHER_USER_ID, "group.member", "group.png");
        when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(CREATOR_ID));
        when(loadSettlementPort.findByIdForUpdate(SETTLEMENT_ID))
                .thenReturn(Optional.of(draftSettlement()));
        when(loadGroupMembersPort.findActiveRoster(groupId)).thenReturn(Optional.of(
                new GroupMemberRoster(groupId, List.of(CREATOR_ID, OTHER_USER_ID))
        ));
        when(loadUserPort.findByPublicId("group.member")).thenReturn(Optional.of(groupMember));
        when(loadSettlementParticipantPort.nextDisplayOrder(SETTLEMENT_ID)).thenReturn(3);
        when(saveSettlementParticipantPort.save(any(SettlementParticipant.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var result = settlementParticipantService.add(
                SETTLEMENT_ID,
                new AddSettlementParticipantCommand(
                        SettlementParticipantSource.GROUP_MEMBER,
                        groupId,
                        "GROUP.MEMBER",
                        null,
                        null
                )
        );

        assertThat(result.source()).isEqualTo(SettlementParticipantSource.GROUP_MEMBER);
        assertThat(result.publicId()).isEqualTo("group.member");
        assertThat(result.displayName()).isEqualTo("회원 3");
        assertThat(result.displayOrder()).isEqualTo(3);
    }

    @Test
    @DisplayName("그룹에 속하지 않은 회원은 참여자로 추가하지 않는다")
    void rejectsUserOutsideSelectedGroup() {
        Long groupId = 10L;
        User outsider = user(OTHER_USER_ID, "group.outsider", null);
        when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(CREATOR_ID));
        when(loadSettlementPort.findByIdForUpdate(SETTLEMENT_ID))
                .thenReturn(Optional.of(draftSettlement()));
        when(loadGroupMembersPort.findActiveRoster(groupId)).thenReturn(Optional.of(
                new GroupMemberRoster(groupId, List.of(CREATOR_ID))
        ));
        when(loadUserPort.findByPublicId("group.outsider")).thenReturn(Optional.of(outsider));

        assertThatThrownBy(() -> settlementParticipantService.add(
                SETTLEMENT_ID,
                new AddSettlementParticipantCommand(
                        SettlementParticipantSource.GROUP_MEMBER,
                        groupId,
                        "group.outsider",
                        null,
                        null
                )
        )).isInstanceOfSatisfying(ApplicationException.class, exception ->
                assertThat(exception.getStatusCode()).isEqualTo(
                        SettlementStatusCode.SETTLEMENT_PARTICIPANT_CANDIDATE_NOT_FOUND
                ));
        verifyNoInteractions(saveSettlementParticipantPort);
    }

    private Settlement settlement() {
        return new Settlement(
                SETTLEMENT_ID,
                CREATOR_ID,
                "참여자 조회 테스트",
                30_000,
                SettlementStatus.REQUESTED,
                CREATED_AT,
                CREATED_AT
        );
    }

    private Settlement draftSettlement() {
        return new Settlement(
                SETTLEMENT_ID,
                CREATOR_ID,
                "참여자 검색 테스트",
                30_000,
                SettlementStatus.DRAFT,
                CREATED_AT,
                CREATED_AT
        );
    }

    private SettlementParticipant registeredCreator() {
        return new SettlementParticipant(
                CREATOR_PARTICIPANT_ID,
                SETTLEMENT_ID,
                SettlementParticipantSource.REGISTERED_USER,
                CREATOR_ID,
                null,
                "최수아",
                0,
                CREATED_AT,
                CREATED_AT
        );
    }

    private SettlementParticipant linkedContact() {
        return new SettlementParticipant(
                LINKED_PARTICIPANT_ID,
                SETTLEMENT_ID,
                SettlementParticipantSource.CONTACT,
                LINKED_USER_ID,
                null,
                "연락처 회원",
                1,
                CREATED_AT,
                CREATED_AT
        );
    }

    private SettlementParticipant unlinkedContact() {
        return unlinkedContact("a".repeat(64));
    }

    private SettlementParticipant unlinkedContact(String phoneHash) {
        return new SettlementParticipant(
                CONTACT_PARTICIPANT_ID,
                SETTLEMENT_ID,
                SettlementParticipantSource.CONTACT,
                null,
                phoneHash,
                "비회원 연락처",
                2,
                CREATED_AT,
                CREATED_AT
        );
    }

    private User creator() {
        return user(CREATOR_ID, "creator.id", null);
    }

    private SettlementContactCommand contact(
            String contactId,
            String displayName,
            String phoneNumber
    ) {
        return new SettlementContactCommand(contactId, displayName, phoneNumber);
    }

    private User linkedUser(String avatarFileName) {
        return user(LINKED_USER_ID, "linked.id", avatarFileName);
    }

    private User user(Long id, String publicId, String avatarFileName) {
        return user(id, "identity-key-" + id, publicId, avatarFileName);
    }

    private User user(Long id, String identityKey, String publicId, String avatarFileName) {
        return new User(
                id,
                identityKey,
                "회원 " + id,
                publicId,
                null,
                null,
                "0101234567" + id,
                "encoded-pin",
                null,
                avatarFileName,
                true,
                UserRole.USER,
                true,
                null,
                CREATED_AT,
                null,
                CREATED_AT,
                CREATED_AT
        );
    }
}
