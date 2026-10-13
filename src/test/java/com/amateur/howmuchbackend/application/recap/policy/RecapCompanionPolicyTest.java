package com.amateur.howmuchbackend.application.recap.policy;

import com.amateur.howmuchbackend.application.recap.model.RecapSourceFact;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class RecapCompanionPolicyTest {
    private final RecapCompanionPolicy policy = new RecapCompanionPolicy();
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 1, 0, 0);

    @Test
    void groupsGuestsAndCountsOncePerSettlementUsingRecentOriginalName() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        var result = policy.select(1L, List.of(fact(first, NOW, null, "A B", 100L),
                fact(first, NOW, null, "a b", 100L), fact(second, NOW.plusDays(1), null, "AB", 0L),
                fact(first, NOW, 1L, "나", 100L)));
        assertThat(result.identityKey()).isEqualTo("GUEST:ab");
        assertThat(result.displayName()).isEqualTo("AB");
        assertThat(result.shareDisplayName()).isEqualTo("A*");
        assertThat(result.completedSettlementCount()).isEqualTo(2);
        assertThat(result.totalOwnAmount()).isEqualTo(100);
        assertThat(policy.select(1L, List.of())).isNull();
    }

    @Test
    void memberAndGuestHaveSeparateIdentitiesAndWithdrawnMemberHidesPublicId() {
        UUID id = UUID.randomUUID();
        var member = policy.select(1L, List.of(fact(id, NOW, 2L, "김가", 100L)));
        assertThat(member.displayName()).isEqualTo("계정 김가");
        assertThat(member.shareDisplayName()).isEqualTo("member_2");
        var withdrawn = new RecapSourceFact(id, NOW, 100L, false, UUID.randomUUID(),
                2L, "원래 이름", "탈퇴", "del_private", "avatar.png", false);
        var result = policy.select(1L, List.of(withdrawn));
        assertThat(result.kind().name()).isEqualTo("WITHDRAWN_MEMBER");
        assertThat(result.displayName()).isEqualTo("탈퇴한 회원");
        assertThat(result.shareDisplayName()).isEqualTo("탈퇴한 회원");
        assertThat(result.avatarFileName()).isNull();
        var mixed = policy.select(1L, List.of(fact(id, NOW, null, "계정 김가", 100L),
                fact(id, NOW, 2L, "김가", 100L)));
        assertThat(mixed.identityKey()).isEqualTo("GUEST:계정김가");
    }

    @Test
    void ranksByCountThenOwnAmountThenNameThenIdentity() {
        UUID one = UUID.randomUUID(), two = UUID.randomUUID();
        assertThat(policy.select(1L, List.of(fact(one, NOW, 2L, "나", 100L),
                fact(two, NOW, 2L, "나", 0L), fact(one, NOW, 3L, "가", 100L)))
                .identityKey()).isEqualTo("MEMBER:2");
        assertThat(policy.select(1L, List.of(fact(one, NOW, 2L, "나", 100L),
                fact(two, NOW, 3L, "가", 200L))).identityKey()).isEqualTo("MEMBER:3");
        assertThat(policy.select(1L, List.of(fact(one, NOW, 2L, "나", 100L),
                fact(one, NOW, 3L, "가", 100L))).identityKey()).isEqualTo("MEMBER:3");
        assertThat(policy.select(1L, List.of(fact(one, NOW, 3L, "가", 100L),
                fact(one, NOW, 2L, "가", 100L))).identityKey()).isEqualTo("MEMBER:2");
    }

    @Test
    void sameTimestampUsesSettlementAndParticipantIdsRegardlessOfInputOrder() {
        UUID first = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID last = UUID.fromString("00000000-0000-0000-0000-000000000002");
        var a = fact(first, NOW, null, "Ab", 100L);
        var b = fact(last, NOW, null, "aB", 100L);
        assertThat(policy.select(1L, List.of(b, a)).displayName()).isEqualTo("aB");
        assertThat(policy.select(1L, List.of(a, b)).displayName()).isEqualTo("aB");
    }

    @ParameterizedTest
    @CsvSource({"김, *", "김가, 김*", "김가나, 김*나", "김가나다, 김**다", "😀가😀, 😀*😀"})
    void masksByUnicodeCodePoint(String name, String masked) {
        assertThat(RecapCompanionPolicy.maskGuest(name)).isEqualTo(masked);
    }

    private RecapSourceFact fact(UUID id, LocalDateTime at, Long userId, String name, Long amount) {
        return new RecapSourceFact(id, at, amount, false, UUID.randomUUID(), userId,
                name, "계정 " + name, "member_" + userId, null, true);
    }
}
