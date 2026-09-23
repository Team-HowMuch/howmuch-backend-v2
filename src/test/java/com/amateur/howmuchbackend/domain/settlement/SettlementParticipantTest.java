package com.amateur.howmuchbackend.domain.settlement;

import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.settlement.status.SettlementStatusCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("정산 참여자 도메인 정책")
class SettlementParticipantTest {
    private static final UUID SETTLEMENT_ID =
            UUID.fromString("44c9323d-6a65-4a93-aeaa-ef8f24341c8a");
    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 8, 22, 12, 0);
    private static final String PHONE_HASH = "a".repeat(SettlementParticipant.PHONE_HASH_LENGTH);

    @Test
    @DisplayName("등록 사용자와 그룹 구성원은 회원 ID로 식별한다")
    void participant_registeredUserAndGroupMember_identifiedByUserId() {
        SettlementParticipant registered = SettlementParticipant.registeredUser(
                SETTLEMENT_ID,
                1L,
                " \u3000최수아\u3000 ",
                0,
                CREATED_AT
        );
        SettlementParticipant groupMember = SettlementParticipant.groupMember(
                SETTLEMENT_ID,
                10L,
                2L,
                "문채원",
                1,
                CREATED_AT
        );

        assertThat(registered.source()).isEqualTo(SettlementParticipantSource.REGISTERED_USER);
        assertThat(registered.linkedUserId()).isEqualTo(1L);
        assertThat(registered.phoneHash()).isNull();
        assertThat(registered.displayName()).isEqualTo("최수아");
        assertThat(registered.isLinkedTo(1L)).isTrue();
        assertThat(groupMember.source()).isEqualTo(SettlementParticipantSource.GROUP_MEMBER);
        assertThat(groupMember.sourceGroupId()).isEqualTo(10L);
        assertThat(groupMember.isLinkedTo(2L)).isTrue();
    }

    @Test
    @DisplayName("연락처 회원은 회원 ID로, 비회원 연락처는 전화번호 hash로 식별한다")
    void participant_linkedAndUnlinkedContact_identifiersAssigned() {
        SettlementParticipant linked = SettlementParticipant.linkedContact(
                SETTLEMENT_ID,
                1L,
                "회원 연락처",
                0,
                CREATED_AT
        );
        SettlementParticipant unlinked = SettlementParticipant.unlinkedContact(
                SETTLEMENT_ID,
                PHONE_HASH,
                "비회원 연락처",
                1,
                CREATED_AT
        );

        assertThat(linked.source()).isEqualTo(SettlementParticipantSource.CONTACT);
        assertThat(linked.linkedUserId()).isEqualTo(1L);
        assertThat(linked.phoneHash()).isNull();
        assertThat(unlinked.source()).isEqualTo(SettlementParticipantSource.CONTACT);
        assertThat(unlinked.linkedUserId()).isNull();
        assertThat(unlinked.phoneHash()).isEqualTo(PHONE_HASH);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   ", "\u2003", "\u3000"})
    @DisplayName("브라우저에서 참여자를 구분할 수 있도록 빈 표시 이름을 거부한다")
    void participant_blankDisplayName_rejected(String displayName) {
        assertThatThrownBy(() -> SettlementParticipant.registeredUser(
                SETTLEMENT_ID,
                1L,
                displayName,
                0,
                CREATED_AT
        )).isInstanceOfSatisfying(ApplicationException.class, exception ->
                assertThat(exception.getStatusCode())
                        .isEqualTo(SettlementStatusCode.INVALID_SETTLEMENT_PARTICIPANT_DISPLAY_NAME));
    }

    @ParameterizedTest
    @ValueSource(strings = {"short", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"})
    @DisplayName("비회원 연락처에 hash를 주면 64자 소문자 hex만 식별자로 사용한다")
    void participant_phoneHash_invalidFormat_rejected(String phoneHash) {
        assertThatThrownBy(() -> SettlementParticipant.unlinkedContact(
                SETTLEMENT_ID,
                phoneHash,
                "비회원 연락처",
                0,
                CREATED_AT
        )).isInstanceOfSatisfying(ApplicationException.class, exception ->
                assertThat(exception.getStatusCode())
                        .isEqualTo(SettlementStatusCode.INVALID_SETTLEMENT_PARTICIPANT_IDENTITY));
    }

    @Test
    @DisplayName("연락처에 없는 사람은 phone hash 없이 이름만으로 추가할 수 있다")
    void participant_contactWithoutPhoneHash_allowed() {
        SettlementParticipant participant = SettlementParticipant.unlinkedContact(
                SETTLEMENT_ID,
                null,
                "친구 1",
                0,
                CREATED_AT
        );

        assertThat(participant.source()).isEqualTo(SettlementParticipantSource.CONTACT);
        assertThat(participant.linkedUserId()).isNull();
        assertThat(participant.phoneHash()).isNull();
        assertThat(participant.displayName()).isEqualTo("친구 1");
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    @DisplayName("등록 참여자는 양수 회원 ID가 있어야 한다")
    void participant_linkedUserId_nonPositive_rejected(long linkedUserId) {
        assertThatThrownBy(() -> SettlementParticipant.registeredUser(
                SETTLEMENT_ID,
                linkedUserId,
                "등록 참여자",
                0,
                CREATED_AT
        )).isInstanceOfSatisfying(ApplicationException.class, exception ->
                assertThat(exception.getStatusCode())
                        .isEqualTo(SettlementStatusCode.INVALID_SETTLEMENT_PARTICIPANT_IDENTITY));
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    @DisplayName("그룹 참여자는 양수 출처 그룹 ID가 있어야 한다")
    void participant_sourceGroupId_nonPositive_rejected(long sourceGroupId) {
        assertThatThrownBy(() -> SettlementParticipant.groupMember(
                SETTLEMENT_ID,
                sourceGroupId,
                2L,
                "그룹 참여자",
                0,
                CREATED_AT
        )).isInstanceOfSatisfying(ApplicationException.class, exception ->
                assertThat(exception.getStatusCode())
                        .isEqualTo(SettlementStatusCode.INVALID_SETTLEMENT_PARTICIPANT_IDENTITY));
    }

    @Test
    @DisplayName("참여자 표시 순서는 첫 참여자부터 증가하도록 음수를 거부한다")
    void rejectsNegativeDisplayOrder() {
        assertThatThrownBy(() -> SettlementParticipant.registeredUser(
                SETTLEMENT_ID,
                1L,
                "등록 참여자",
                -1,
                CREATED_AT
        )).isInstanceOfSatisfying(ApplicationException.class, exception ->
                assertThat(exception.getStatusCode())
                        .isEqualTo(SettlementStatusCode.INVALID_SETTLEMENT_PARTICIPANT_ORDER));
    }
}
