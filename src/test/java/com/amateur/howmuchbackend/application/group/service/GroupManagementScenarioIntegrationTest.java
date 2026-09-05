package com.amateur.howmuchbackend.application.group.service;

import com.amateur.howmuchbackend.adapter.out.persistence.external.CodefSecretCipher;
import com.amateur.howmuchbackend.application.port.in.group.CreateGroupUseCase;
import com.amateur.howmuchbackend.application.port.in.group.DeleteGroupUseCase;
import com.amateur.howmuchbackend.application.port.in.group.GetGroupUseCase;
import com.amateur.howmuchbackend.application.port.in.group.JoinGroupUseCase;
import com.amateur.howmuchbackend.application.port.in.group.LeaveGroupUseCase;
import com.amateur.howmuchbackend.application.port.in.group.command.CreateGroupCommand;
import com.amateur.howmuchbackend.application.port.in.group.command.JoinGroupCommand;
import com.amateur.howmuchbackend.application.port.in.settlement.AddSettlementParticipantUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.CreateSettlementUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.GetSettlementGroupParticipantCandidatesUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.GetSettlementUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.command.AddSettlementParticipantCommand;
import com.amateur.howmuchbackend.application.port.in.settlement.command.CreateSettlementCommand;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.group.status.GroupStatusCode;
import com.amateur.howmuchbackend.domain.settlement.SettlementParticipantSource;
import com.amateur.howmuchbackend.domain.settlement.SettlementStatus;
import com.amateur.howmuchbackend.infra.security.jwt.JwtPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@TestPropertySource(properties = {
        "howmuch.auth.sms.discord.webhook-url=http://127.0.0.1:9/unused",
        "howmuch.codef.connected-id-encryption-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="
})
@DisplayName("그룹 관리 전체 시나리오 통합")
class GroupManagementScenarioIntegrationTest {
    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 8, 25, 10, 0);

    @Autowired private CreateGroupUseCase createGroupUseCase;
    @Autowired private JoinGroupUseCase joinGroupUseCase;
    @Autowired private GetGroupUseCase getGroupUseCase;
    @Autowired private LeaveGroupUseCase leaveGroupUseCase;
    @Autowired private DeleteGroupUseCase deleteGroupUseCase;
    @Autowired private CreateSettlementUseCase createSettlementUseCase;
    @Autowired private GetSettlementUseCase getSettlementUseCase;
    @Autowired private GetSettlementGroupParticipantCandidatesUseCase groupCandidatesUseCase;
    @Autowired private AddSettlementParticipantUseCase addSettlementParticipantUseCase;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private CodefSecretCipher cipher;

    private final List<Long> userIds = new ArrayList<>();
    private Long groupId;
    private UUID settlementId;
    private Long accountRecordId;

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        if (settlementId != null) {
            jdbcTemplate.update("DELETE FROM settlements WHERE id = ?", settlementId);
        }
        if (accountRecordId != null) {
            jdbcTemplate.update("DELETE FROM account_records WHERE id = ?", accountRecordId);
        }
        if (groupId != null) {
            jdbcTemplate.update("DELETE FROM group_members WHERE group_id = ?", groupId);
            jdbcTemplate.update("DELETE FROM groups WHERE id = ?", groupId);
        }
        userIds.forEach(id -> jdbcTemplate.update("DELETE FROM users WHERE id = ?", id));
    }

    @Test
    @DisplayName("가입한 그룹멤버를 정산에 연결하고 종료 정책과 그룹 삭제 이후 보존을 검증한다")
    void preservesSettlementAcrossGroupMemberLifecycle() {
        UserFixture owner = insertUser("최수아");
        UserFixture member = insertUser("그룹 멤버");

        authenticate(owner.id());
        var group = createGroupUseCase.create(new CreateGroupCommand(
                "제주 여행",
                "통합 검증",
                null
        ));
        groupId = group.groupId();

        authenticate(member.id());
        var joined = joinGroupUseCase.join(new JoinGroupCommand(
                "  " + group.inviteCode().toLowerCase() + "  "
        ));
        assertThat(joined.memberCount()).isEqualTo(2);

        authenticate(owner.id());
        var settlement = createSettlementUseCase.create(new CreateSettlementCommand(
                "제주 여행 정산",
                30_000
        ));
        settlementId = settlement.settlementId();

        assertThat(groupCandidatesUseCase.get(settlementId, groupId))
                .extracting(candidate -> candidate.publicId())
                .containsExactly(member.publicId());

        var participant = addSettlementParticipantUseCase.add(
                settlementId,
                new AddSettlementParticipantCommand(
                        SettlementParticipantSource.GROUP_MEMBER,
                        groupId,
                        member.publicId(),
                        null,
                        null
                )
        );
        assertThat(participant.source()).isEqualTo(SettlementParticipantSource.GROUP_MEMBER);
        assertThat(sourceGroupId(participant.participantId())).isEqualTo(groupId);

        jdbcTemplate.update(
                "UPDATE settlements SET status = 'REQUESTED' WHERE id = ?",
                settlementId
        );
        authenticate(member.id());
        assertThatThrownBy(() -> leaveGroupUseCase.leave(groupId))
                .isInstanceOfSatisfying(ApplicationException.class, exception ->
                        assertThat(exception.getStatusCode())
                                .isEqualTo(GroupStatusCode.GROUP_MEMBER_EXIT_BLOCKED_BY_SETTLEMENT)
                );

        accountRecordId = -owner.id();
        jdbcTemplate.update("""
                INSERT INTO account_records (
                    id, user_id, organization, type, name, masked_number, currency,
                    linked_at, deleted_at
                ) VALUES (?, ?, '0020', 'BANK', '통합 테스트 계좌', '***', 'KRW', ?, NULL)
                """, accountRecordId, owner.id(), CREATED_AT);
        jdbcTemplate.update("""
                INSERT INTO settlement_receiving_account_snapshots (
                    settlement_id, source_account_id, organization,
                    account_number_ciphertext, account_holder, created_at
                ) VALUES (?, ?, '0020', ?, '최수아', ?)
                """,
                settlementId,
                accountRecordId,
                cipher.encryptSettlementAccountNumber(settlementId, "1002123456789"),
                CREATED_AT
        );
        jdbcTemplate.update(
                "UPDATE settlements SET status = 'COMPLETED' WHERE id = ?",
                settlementId
        );
        leaveGroupUseCase.leave(groupId);
        assertThat(memberStatus(member.id())).isEqualTo("LEFT");

        authenticate(owner.id());
        deleteGroupUseCase.delete(groupId);
        assertThatThrownBy(() -> getGroupUseCase.get(groupId))
                .isInstanceOfSatisfying(ApplicationException.class, exception ->
                        assertThat(exception.getStatusCode()).isEqualTo(GroupStatusCode.GROUP_NOT_FOUND)
                );
        assertThat(getSettlementUseCase.get(settlementId).status())
                .isEqualTo(SettlementStatus.COMPLETED);
        assertThat(sourceGroupId(participant.participantId())).isEqualTo(groupId);
    }

    private UserFixture insertUser(String name) {
        String uniqueKey = UUID.randomUUID().toString();
        String publicId = "user_" + uniqueKey.replace("-", "").substring(0, 12);
        Long userId = jdbcTemplate.queryForObject("""
                        INSERT INTO users (
                            identity_key,
                            name,
                            public_id,
                            phone_number,
                            pin_hash,
                            phone_verified,
                            role,
                            active,
                            required_terms_agreed_at,
                            created_at,
                            updated_at
                        )
                        VALUES (?, ?, ?, ?, ?, TRUE, 'USER', TRUE, ?, ?, ?)
                        RETURNING id
                        """,
                Long.class,
                "group-scenario-" + uniqueKey,
                name,
                publicId,
                "test-" + uniqueKey.substring(0, 12),
                "not-used",
                CREATED_AT,
                CREATED_AT,
                CREATED_AT
        );
        userIds.add(userId);
        return new UserFixture(userId, publicId);
    }

    private void authenticate(Long userId) {
        var authentication = new UsernamePasswordAuthenticationToken(
                new JwtPrincipal(userId, "group-scenario"),
                null,
                AuthorityUtils.NO_AUTHORITIES
        );
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    private Long sourceGroupId(UUID participantId) {
        return jdbcTemplate.queryForObject(
                "SELECT source_group_id FROM settlement_participants WHERE id = ?",
                Long.class,
                participantId
        );
    }

    private String memberStatus(Long memberUserId) {
        return jdbcTemplate.queryForObject("""
                        SELECT status
                          FROM group_members
                         WHERE group_id = ?
                           AND user_id = ?
                        """,
                String.class,
                groupId,
                memberUserId
        );
    }

    private record UserFixture(Long id, String publicId) {
    }
}
