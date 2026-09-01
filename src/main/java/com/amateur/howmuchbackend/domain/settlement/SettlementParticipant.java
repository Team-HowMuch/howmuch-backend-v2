package com.amateur.howmuchbackend.domain.settlement;

import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.settlement.status.SettlementStatusCode;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

public record SettlementParticipant(
        UUID id,
        UUID settlementId,
        SettlementParticipantSource source,
        Long linkedUserId,
        String phoneHash,
        Long sourceGroupId,
        String displayName,
        int displayOrder,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static final int MAX_DISPLAY_NAME_LENGTH = 100;
    public static final int PHONE_HASH_LENGTH = 64;
    private static final Pattern PHONE_HASH_PATTERN = Pattern.compile("^[0-9a-f]{64}$");

    public SettlementParticipant {
        Objects.requireNonNull(id, "정산 참여자 ID는 필수입니다.");
        Objects.requireNonNull(settlementId, "정산 ID는 필수입니다.");
        Objects.requireNonNull(source, "정산 참여자 선택 경로는 필수입니다.");
        Objects.requireNonNull(createdAt, "정산 참여자 생성 시각은 필수입니다.");
        Objects.requireNonNull(updatedAt, "정산 참여자 수정 시각은 필수입니다.");
        displayName = normalizeDisplayName(displayName);
        validateDisplayOrder(displayOrder);
        validateIdentity(source, linkedUserId, phoneHash, sourceGroupId);
    }

    public static SettlementParticipant registeredUser(
            UUID settlementId,
            Long linkedUserId,
            String displayName,
            int displayOrder,
            LocalDateTime now
    ) {
        return create(
                settlementId,
                SettlementParticipantSource.REGISTERED_USER,
                linkedUserId,
                null,
                null,
                displayName,
                displayOrder,
                now
        );
    }

    public static SettlementParticipant groupMember(
            UUID settlementId,
            Long sourceGroupId,
            Long linkedUserId,
            String displayName,
            int displayOrder,
            LocalDateTime now
    ) {
        return create(
                settlementId,
                SettlementParticipantSource.GROUP_MEMBER,
                linkedUserId,
                null,
                sourceGroupId,
                displayName,
                displayOrder,
                now
        );
    }

    public static SettlementParticipant linkedContact(
            UUID settlementId,
            Long linkedUserId,
            String displayName,
            int displayOrder,
            LocalDateTime now
    ) {
        return create(
                settlementId,
                SettlementParticipantSource.CONTACT,
                linkedUserId,
                null,
                null,
                displayName,
                displayOrder,
                now
        );
    }

    public static SettlementParticipant unlinkedContact(
            UUID settlementId,
            String phoneHash,
            String displayName,
            int displayOrder,
            LocalDateTime now
    ) {
        return create(
                settlementId,
                SettlementParticipantSource.CONTACT,
                null,
                phoneHash,
                null,
                displayName,
                displayOrder,
                now
        );
    }

    public boolean isLinkedTo(Long userId) {
        return linkedUserId != null && linkedUserId.equals(userId);
    }

    public SettlementParticipant rename(String newDisplayName, LocalDateTime now) {
        Objects.requireNonNull(now, "정산 참여자 수정 시각은 필수입니다.");
        return new SettlementParticipant(
                id,
                settlementId,
                source,
                linkedUserId,
                phoneHash,
                sourceGroupId,
                newDisplayName,
                displayOrder,
                createdAt,
                now
        );
    }

    private static SettlementParticipant create(
            UUID settlementId,
            SettlementParticipantSource source,
            Long linkedUserId,
            String phoneHash,
            Long sourceGroupId,
            String displayName,
            int displayOrder,
            LocalDateTime now
    ) {
        Objects.requireNonNull(now, "정산 참여자 생성 시각은 필수입니다.");
        return new SettlementParticipant(
                UUID.randomUUID(),
                settlementId,
                source,
                linkedUserId,
                phoneHash,
                sourceGroupId,
                displayName,
                displayOrder,
                now,
                now
        );
    }

    private static String normalizeDisplayName(String displayName) {
        if (displayName == null) {
            throw ApplicationException.of(SettlementStatusCode.INVALID_SETTLEMENT_PARTICIPANT_DISPLAY_NAME);
        }
        String normalized = displayName.strip();
        if (normalized.isBlank() || normalized.length() > MAX_DISPLAY_NAME_LENGTH) {
            throw ApplicationException.of(SettlementStatusCode.INVALID_SETTLEMENT_PARTICIPANT_DISPLAY_NAME);
        }
        return normalized;
    }

    private static void validateDisplayOrder(int displayOrder) {
        if (displayOrder < 0) {
            throw ApplicationException.of(SettlementStatusCode.INVALID_SETTLEMENT_PARTICIPANT_ORDER);
        }
    }

    private static void validateIdentity(
            SettlementParticipantSource source,
            Long linkedUserId,
            String phoneHash,
            Long sourceGroupId
    ) {
        boolean hasValidLinkedUser = linkedUserId != null && linkedUserId > 0;
        boolean hasValidPhoneHash = phoneHash != null && PHONE_HASH_PATTERN.matcher(phoneHash).matches();

        boolean valid = switch (source) {
            case REGISTERED_USER -> hasValidLinkedUser && phoneHash == null && sourceGroupId == null;
            case GROUP_MEMBER -> hasValidLinkedUser
                    && phoneHash == null
                    && sourceGroupId != null
                    && sourceGroupId > 0;
            // 연락처에 없는 사람은 이름만으로 추가할 수 있어 셋 다 비어 있을 수 있다.
            case CONTACT -> sourceGroupId == null
                    && ((hasValidLinkedUser && phoneHash == null)
                    || (linkedUserId == null && hasValidPhoneHash)
                    || (linkedUserId == null && phoneHash == null));
        };
        if (!valid) {
            throw ApplicationException.of(SettlementStatusCode.INVALID_SETTLEMENT_PARTICIPANT_IDENTITY);
        }
    }
}
