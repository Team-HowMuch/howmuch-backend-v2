package com.amateur.howmuchbackend.adapter.in.web.group;

import com.amateur.howmuchbackend.application.auth.model.CurrentActor;
import com.amateur.howmuchbackend.application.group.service.GroupMembershipService;
import com.amateur.howmuchbackend.application.group.service.GroupMemberExitService;
import com.amateur.howmuchbackend.application.port.in.group.command.JoinGroupCommand;
import com.amateur.howmuchbackend.application.group.policy.GroupInviteCodePolicy;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.application.port.out.group.*;
import com.amateur.howmuchbackend.application.port.out.user.SaveUserPort;
import com.amateur.howmuchbackend.domain.group.*;
import com.amateur.howmuchbackend.domain.user.User;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"howmuch.settlement.notification-outbox.enabled=false"})
@AutoConfigureMockMvc
class GroupInvitationHttpIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired SaveUserPort users;
    @Autowired GroupMembershipService membership;
    @Autowired GroupMemberExitService exit;
    @Autowired SaveGroupPort groups;
    @Autowired SaveGroupMemberPort members;
    @Autowired LoadGroupMemberPort loadMembers;
    @Autowired GroupInvitationStorePort invitations;
    @Autowired GroupInviteCodePolicy codes;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired ObjectMapper json;
    @MockitoBean CurrentActorPort actor;
    private final ThreadLocal<Long> actorId = new ThreadLocal<>();
    private User owner, recipient, outsider;
    private Group group;
    private final LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
    @BeforeEach void setUp() {
        when(actor.getCurrentActor()).thenAnswer(invocation -> new CurrentActor(actorId.get()));
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            owner = createUser("방장"); recipient = createUser("초대 대상"); outsider = createUser("외부인");
            group = groups.save(Group.create("초대 테스트", null, null, owner.id(), codes.generate(), now));
            members.save(GroupMember.owner(group.id(), owner.id(), now));
        });
        actorId.set(owner.id());
    }
    @AfterEach void cleanUp() {
        jdbc.update("DELETE FROM settlement_notification_outbox WHERE recipient_user_id IN (?,?,?)", owner.id(),recipient.id(),outsider.id());
        jdbc.update("DELETE FROM user_notifications WHERE recipient_user_id IN (?,?,?)", owner.id(),recipient.id(),outsider.id());
        jdbc.update("DELETE FROM group_invitations WHERE group_id=?", group.id());
        jdbc.update("DELETE FROM group_members WHERE group_id=?", group.id());
        jdbc.update("DELETE FROM groups WHERE id=?", group.id());
        jdbc.update("DELETE FROM users WHERE id IN (?,?,?)", owner.id(),recipient.id(),outsider.id());
        actorId.remove();
    }
    @Test void createRepeatAndAccept_actualRowsRemainUnique() throws Exception {
        UUID first = create(); UUID second = create(); assertThat(second).isEqualTo(first);
        assertThat(loadMembers.findByGroupIdAndUserId(group.id(), recipient.id())).isEmpty();
        assertThat(count("user_notifications")).isOne(); assertThat(count("settlement_notification_outbox")).isOne();
        actorId.set(outsider.id());
        mvc.perform(get("/api/v2/group-invitations/{id}",first).with(user("outsider"))).andExpect(status().isNotFound());
        actorId.set(recipient.id());
        mvc.perform(get("/api/v2/group-invitations/{id}",first).with(user("recipient")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("PENDING"));
        mvc.perform(post("/api/v2/group-invitations/{id}/accept",first).with(user("recipient")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("ACCEPTED"));
        var member = loadMembers.findByGroupIdAndUserId(group.id(),recipient.id()).orElseThrow();
        mvc.perform(post("/api/v2/group-invitations/{id}/accept",first).with(user("recipient"))).andExpect(status().isOk());
        assertThat(loadMembers.findByGroupIdAndUserId(group.id(),recipient.id()).orElseThrow().id()).isEqualTo(member.id());
        mvc.perform(post("/api/v2/group-invitations/{id}/decline",first).with(user("recipient"))).andExpect(status().isConflict());
    }
    @Test void expiredInvitation_rejectedAndNewInvitationCreated() throws Exception {
        UUID id = create();
        jdbc.update("UPDATE group_invitations SET created_at=?,expires_at=? WHERE id=?", now.minusDays(8),now.minusDays(1),id);
        actorId.set(recipient.id());
        mvc.perform(get("/api/v2/group-invitations/{id}",id).with(user("recipient")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("EXPIRED"));
        mvc.perform(post("/api/v2/group-invitations/{id}/accept",id).with(user("recipient"))).andExpect(status().isGone());
        actorId.set(owner.id()); assertThat(create()).isNotEqualTo(id);
        assertThat(invitations.findById(id).orElseThrow().status()).isEqualTo(GroupInvitationStatus.EXPIRED);
    }
    @Test void declinedInvitation_neverCreatesMember() throws Exception {
        UUID id=create(); actorId.set(recipient.id());
        for (int i=0;i<2;i++) mvc.perform(post("/api/v2/group-invitations/{id}/decline",id).with(user("recipient")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("DECLINED"));
        assertThat(loadMembers.findByGroupIdAndUserId(group.id(),recipient.id())).isEmpty();
        mvc.perform(post("/api/v2/group-invitations/{id}/accept",id).with(user("recipient"))).andExpect(status().isConflict());
    }
    @Test void unauthorizedCreationAndValidation_areEnforced() throws Exception {
        mvc.perform(post("/api/v2/groups/{id}/invitations",group.id()).contentType("application/json").content("{\"publicId\":\"unused\"}"))
                .andExpect(status().isUnauthorized());
        actorId.set(outsider.id());
        mvc.perform(post("/api/v2/groups/{id}/invitations",group.id()).with(user("outsider")).contentType("application/json").content("{\"publicId\":\""+recipient.publicId()+"\"}"))
                .andExpect(status().isForbidden());
        actorId.set(owner.id());
        mvc.perform(post("/api/v2/groups/{id}/invitations",group.id()).with(user("owner")).contentType("application/json").content("{\"publicId\":\" \"}"))
                .andExpect(status().isBadRequest());
    }
    @Test void oldInvitation_cannotBypassRemovalAfterCodeJoin() throws Exception {
        UUID old=create(); actorId.set(recipient.id());
        membership.join(new JoinGroupCommand(group.inviteCode()));
        assertThat(invitations.findById(old).orElseThrow().status()).isEqualTo(GroupInvitationStatus.CANCELLED);
        var member=loadMembers.findByGroupIdAndUserId(group.id(),recipient.id()).orElseThrow();
        actorId.set(owner.id()); exit.remove(group.id(),member.id());
        actorId.set(recipient.id());
        mvc.perform(post("/api/v2/group-invitations/{id}/accept",old).with(user("recipient"))).andExpect(status().isConflict());
        assertThat(loadMembers.findByGroupIdAndUserId(group.id(),recipient.id()).orElseThrow().status()).isEqualTo(GroupMemberStatus.REMOVED);
        actorId.set(owner.id()); UUID replacement=create(); assertThat(replacement).isNotEqualTo(old);
        actorId.set(recipient.id());
        mvc.perform(post("/api/v2/group-invitations/{id}/accept",replacement).with(user("recipient"))).andExpect(status().isOk());
        assertThat(loadMembers.findByGroupIdAndUserId(group.id(),recipient.id()).orElseThrow().isActive()).isTrue();
    }
    @Test void recorderFailure_rollsBackInvitationAndOutbox() throws Exception {
        // This fixture deliberately forces a database CHECK failure in the same business transaction.
        jdbc.execute("ALTER TABLE user_notifications ADD CONSTRAINT ck_test_invitation_failure CHECK (notification_type <> 'GROUP_INVITATION') NOT VALID");
        try {
            assertThatThrownBy(this::create).isInstanceOf(AssertionError.class);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM group_invitations WHERE group_id=?",Integer.class,group.id())).isZero();
            assertThat(count("user_notifications")).isZero(); assertThat(count("settlement_notification_outbox")).isZero();
        } finally {jdbc.execute("ALTER TABLE user_notifications DROP CONSTRAINT ck_test_invitation_failure");}
    }
    @Test void openApi_documentsCreate201AndAuthenticatedRoutes() throws Exception {
        var document=json.readTree(mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        var operation=document.path("paths").path("/api/v2/groups/{groupId}/invitations").path("post");
        assertThat(operation.path("responses").has("201")).isTrue();
        assertThat(operation.path("security").toString()).contains("bearerAuth");
        assertThat(document.path("paths").path("/api/v2/group-invitations/{invitationId}/accept").path("post").path("responses").has("410")).isTrue();
    }
    @Test void concurrentAccepts_createOneMembership() throws Exception {
        UUID id=create(); ExecutorService pool=Executors.newFixedThreadPool(2);
        try {
            Callable<Integer> accept=() -> {actorId.set(recipient.id()); try {return mvc.perform(post("/api/v2/group-invitations/{id}/accept",id).with(user("recipient"))).andReturn().getResponse().getStatus();} finally {actorId.remove();}};
            var futures=pool.invokeAll(List.of(accept,accept));
            assertThat(futures.get(0).get(20,TimeUnit.SECONDS)).isEqualTo(200);
            assertThat(futures.get(1).get(20,TimeUnit.SECONDS)).isEqualTo(200);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM group_members WHERE group_id=? AND user_id=?",Integer.class,group.id(),recipient.id())).isOne();
        } finally {pool.shutdownNow();}
    }
    private UUID create() throws Exception {
        var result=mvc.perform(post("/api/v2/groups/{id}/invitations",group.id()).with(user("owner")).contentType("application/json")
                .content("{\"publicId\":\""+recipient.publicId()+"\"}"))
                .andExpect(status().isCreated()).andReturn();
        return UUID.fromString(json.readTree(result.getResponse().getContentAsByteArray()).path("data").path("id").asText());
    }
    private int count(String table) {return jdbc.queryForObject("SELECT count(*) FROM "+table+" WHERE recipient_user_id=?",Integer.class,recipient.id());}
    private User createUser(String name) {
        String unique=UUID.randomUUID().toString().replace("-","");
        return users.save(User.register("invitation-"+unique,name,LocalDate.of(1995,1,1),"g"+unique.substring(0,12),"test-"+unique.substring(0,12),"unused",now,false));
    }
}
