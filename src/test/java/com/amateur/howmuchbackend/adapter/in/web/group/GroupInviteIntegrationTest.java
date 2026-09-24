package com.amateur.howmuchbackend.adapter.in.web.group;

import com.amateur.howmuchbackend.application.group.policy.GroupInviteCodePolicy;
import com.amateur.howmuchbackend.application.port.out.group.LoadGroupMemberPort;
import com.amateur.howmuchbackend.application.port.out.group.SaveGroupMemberPort;
import com.amateur.howmuchbackend.application.port.out.group.SaveGroupPort;
import com.amateur.howmuchbackend.application.port.out.group.TransferGroupOwnershipPort;
import com.amateur.howmuchbackend.application.port.out.user.SaveUserPort;
import com.amateur.howmuchbackend.domain.group.Group;
import com.amateur.howmuchbackend.domain.group.GroupMember;
import com.amateur.howmuchbackend.domain.user.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = {
        "howmuch.auth.sms.discord.webhook-url=http://127.0.0.1:9/unused",
        "howmuch.settlement.notification-outbox.enabled=false"
})
class GroupInviteIntegrationTest {
    private static final String PATH = "/api/v2/group-invites/{code}";
    private static final String ORIGIN = "https://howmuch-web.vercel.app";
    private static final LocalDateTime NOW = LocalDateTime.now(ZoneOffset.UTC);
    private final String remoteAddress = "2001:db8:" + UUID.randomUUID();
    private final String quotaKey = "group:invite:requests:" + remoteAddress;

    @Autowired private MockMvc mvc;
    @Autowired private SaveGroupPort groups;
    @Autowired private SaveGroupMemberPort members;
    @Autowired private LoadGroupMemberPort loadMembers;
    @Autowired private SaveUserPort users;
    @Autowired private TransferGroupOwnershipPort ownership;
    @Autowired private GroupInviteCodePolicy codes;
    @Autowired private StringRedisTemplate redis;
    @Autowired private ObjectMapper objectMapper;

    @AfterEach
    void removeOwnQuota() {
        redis.delete(quotaKey);
    }

    @Test
    void invitePreview_anonymousRequest_returnsOwnerWithoutJoining() throws Exception {
        User creator = user("원래 방장");
        User currentOwner = user("현재 방장");
        User formerMember = user("탈퇴 멤버");
        Group group = group(creator.id());
        GroupMember previous = members.save(GroupMember.owner(group.id(), creator.id(), NOW));
        GroupMember next = members.save(GroupMember.member(group.id(), currentOwner.id(), NOW));
        members.save(GroupMember.member(group.id(), formerMember.id(), NOW).leave(NOW));
        ownership.transfer(group.transferOwnership(currentOwner.id(), NOW),
                previous.demoteOwner(NOW), next.promoteToOwner(NOW));
        var before = loadMembers.findActiveByGroupId(group.id());

        mvc.perform(get(PATH, "  " + group.inviteCode().toLowerCase(java.util.Locale.ROOT) + "  ")
                        .header("Origin", ORIGIN)
                        .with(request -> { request.setRemoteAddr(remoteAddress); return request; }))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", ORIGIN))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.groupName").value("여행 모임"))
                .andExpect(jsonPath("$.data.inviterName").value("현재 방장"))
                .andExpect(jsonPath("$.data.memberCount").value(2))
                .andExpect(jsonPath("$.data.groupImageUrl").doesNotExist())
                .andExpect(jsonPath("$.data.groupId").doesNotExist());
        assertThat(loadMembers.findActiveByGroupId(group.id())).isEqualTo(before);
    }

    @Test
    void inviteLookup_missingOrDeleted_returnsNotFound() throws Exception {
        Group deleted = group(user("방장").id());
        groups.update(deleted.delete(NOW));
        for (String code : new String[]{"bad", "        ", codes.generate(), deleted.inviteCode()}) {
            mvc.perform(get(PATH, code)
                            .with(request -> { request.setRemoteAddr(remoteAddress); return request; }))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("GROUP_NOT_FOUND"));
        }
    }

    @Test
    void inviteLookup_failedRequests_rateLimitedAndExpires() throws Exception {
        mvc.perform(get(PATH, "bad")
                        .with(request -> { request.setRemoteAddr(remoteAddress); return request; }))
                .andExpect(status().isNotFound());
        assertThat(redis.getExpire(quotaKey)).isBetween(1L, 60L);
        redis.opsForValue().set(quotaKey, "59", Duration.ofSeconds(60));
        mvc.perform(get(PATH, "bad")
                        .with(request -> { request.setRemoteAddr(remoteAddress); return request; }))
                .andExpect(status().isNotFound());
        mvc.perform(get(PATH, "OTHER123")
                        .header("X-Forwarded-For", "198.51.100.42")
                        .header("Origin", ORIGIN)
                        .with(request -> { request.setRemoteAddr(remoteAddress); return request; }))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "60"))
                .andExpect(header().string("Access-Control-Allow-Origin", ORIGIN))
                .andExpect(jsonPath("$.error.code").value("TOO_MANY_REQUEST"));
        redis.expire(quotaKey, Duration.ZERO);
        mvc.perform(get(PATH, "bad")
                        .with(request -> { request.setRemoteAddr(remoteAddress); return request; }))
                .andExpect(status().isNotFound());
        assertThat(redis.opsForValue().get(quotaKey)).isEqualTo("1");
    }

    @Test
    void invitePreview_onlyRoutePublic_openApiDocumentsContract() throws Exception {
        mvc.perform(options(PATH, "AB12CD34")
                        .header("Origin", ORIGIN)
                        .header("Access-Control-Request-Method", "GET")
                        .with(request -> { request.setRemoteAddr(remoteAddress); return request; }))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", ORIGIN));
        assertThat(redis.hasKey(quotaKey)).isFalse();
        mvc.perform(post(PATH, "AB12CD34")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v2/groups/1/image")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v2/groups/join")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v2/group-invites/AB12CD34/private"))
                .andExpect(status().isUnauthorized());

        var document = objectMapper.readTree(mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        var operation = document.path("paths").path(PATH).path("get");
        assertThat(operation.isMissingNode()).isFalse();
        assertThat(operation.path("security").isMissingNode() || operation.path("security").isEmpty()).isTrue();
        for (String response : new String[]{"200", "404", "429", "503"}) {
            assertThat(operation.path("responses").has(response)).isTrue();
        }
        var properties = document.path("components").path("schemas")
                .path("GroupInviteWebResponse").path("properties");
        assertThat(properties.has("groupName")).isTrue();
        assertThat(properties.has("inviterName")).isTrue();
        assertThat(properties.has("memberCount")).isTrue();
        assertThat(properties.has("groupImageUrl")).isFalse();
    }

    private User user(String name) {
        String unique = UUID.randomUUID().toString().replace("-", "");
        return users.save(User.register("invite-" + unique, name, LocalDate.of(1995, 3, 14), "i" + unique.substring(0, 12),
                "test-" + unique.substring(0, 12), "unused", NOW, false));
    }

    private Group group(Long ownerId) {
        return groups.save(Group.create("여행 모임", null, "private.jpg", ownerId, codes.generate(), NOW));
    }
}
