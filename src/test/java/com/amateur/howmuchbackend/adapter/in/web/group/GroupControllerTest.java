package com.amateur.howmuchbackend.adapter.in.web.group;

import com.amateur.howmuchbackend.application.group.model.GroupImage;
import com.amateur.howmuchbackend.application.port.in.group.CreateGroupUseCase;
import com.amateur.howmuchbackend.application.port.in.group.AddGroupMemberUseCase;
import com.amateur.howmuchbackend.application.port.in.group.DeleteGroupUseCase;
import com.amateur.howmuchbackend.application.port.in.group.GetGroupImageUseCase;
import com.amateur.howmuchbackend.application.port.in.group.GetGroupUseCase;
import com.amateur.howmuchbackend.application.port.in.group.GetMyGroupsUseCase;
import com.amateur.howmuchbackend.application.port.in.group.GetGroupMembersUseCase;
import com.amateur.howmuchbackend.application.port.in.group.JoinGroupUseCase;
import com.amateur.howmuchbackend.application.port.in.group.UpdateGroupUseCase;
import com.amateur.howmuchbackend.application.port.in.group.command.UpdateGroupCommand;
import com.amateur.howmuchbackend.application.port.in.group.command.AddGroupMemberCommand;
import com.amateur.howmuchbackend.application.port.in.group.command.JoinGroupCommand;
import com.amateur.howmuchbackend.application.port.in.group.result.GroupDetailResult;
import com.amateur.howmuchbackend.application.port.in.group.result.GroupSummaryResult;
import com.amateur.howmuchbackend.application.port.in.group.result.GroupMemberResult;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.common.exception.GlobalExceptionHandler;
import com.amateur.howmuchbackend.domain.group.GroupMemberRole;
import com.amateur.howmuchbackend.domain.group.status.GroupStatusCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@DisplayName("그룹 Controller")
class GroupControllerTest {
    @Mock private CreateGroupUseCase createGroupUseCase;
    @Mock private GetMyGroupsUseCase getMyGroupsUseCase;
    @Mock private GetGroupUseCase getGroupUseCase;
    @Mock private GetGroupImageUseCase getGroupImageUseCase;
    @Mock private UpdateGroupUseCase updateGroupUseCase;
    @Mock private DeleteGroupUseCase deleteGroupUseCase;
    @Mock private JoinGroupUseCase joinGroupUseCase;
    @Mock private GetGroupMembersUseCase getGroupMembersUseCase;
    @Mock private AddGroupMemberUseCase addGroupMemberUseCase;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new GroupController(
                createGroupUseCase,
                getMyGroupsUseCase,
                getGroupUseCase,
                getGroupImageUseCase,
                updateGroupUseCase,
                deleteGroupUseCase,
                joinGroupUseCase,
                getGroupMembersUseCase,
                addGroupMemberUseCase
        )).setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    @DisplayName("multipart 그룹 생성 결과를 201로 반환한다")
    void createsGroup() throws Exception {
        when(createGroupUseCase.create(any())).thenReturn(detail("여행", "메모", "/api/v2/groups/10/image"));
        MockMultipartFile image = new MockMultipartFile(
                "image", "group.png", "image/png", new byte[]{1, 2, 3}
        );

        mockMvc.perform(multipart("/api/v2/groups")
                        .file(image)
                        .param("name", "여행")
                        .param("memo", "메모"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.groupId").value(10))
                .andExpect(jsonPath("$.data.inviteCode").value("AB12CD34"))
                .andExpect(jsonPath("$.data.myRole").value("OWNER"));
    }

    @Test
    @DisplayName("내 그룹 목록을 응답 DTO로 변환한다")
    void getsMyGroups() throws Exception {
        when(getMyGroupsUseCase.getMyGroups()).thenReturn(List.of(
                new GroupSummaryResult(20L, "최근 그룹", "/api/v2/groups/20/image"),
                new GroupSummaryResult(10L, "이전 그룹", null)
        ));

        mockMvc.perform(get("/api/v2/groups"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].groupId").value(20))
                .andExpect(jsonPath("$.data[1].imageUrl").isEmpty());
    }

    @Test
    @DisplayName("빈 memo 파트를 전달한 수정 요청은 메모 제거 지시로 변환한다")
    void updatesAndClearsMemo() throws Exception {
        when(updateGroupUseCase.update(any(), any())).thenReturn(detail("여행", null, null));

        mockMvc.perform(multipart(HttpMethod.PATCH, "/api/v2/groups/{groupId}", 10L)
                        .param("memo", ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.memo").isEmpty());

        ArgumentCaptor<UpdateGroupCommand> commandCaptor = ArgumentCaptor.forClass(UpdateGroupCommand.class);
        verify(updateGroupUseCase).update(org.mockito.ArgumentMatchers.eq(10L), commandCaptor.capture());
        assertThat(commandCaptor.getValue().memoProvided()).isTrue();
        assertThat(commandCaptor.getValue().memo()).isEmpty();
    }

    @Test
    @DisplayName("그룹 이미지를 no-store와 실제 content type으로 반환한다")
    void getsGroupImage() throws Exception {
        when(getGroupImageUseCase.getImage(10L))
                .thenReturn(new GroupImage("image/webp", new byte[]{1, 2, 3}));

        mockMvc.perform(get("/api/v2/groups/{groupId}/image", 10L))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().contentType("image/webp"))
                .andExpect(content().bytes(new byte[]{1, 2, 3}));
    }

    @Test
    @DisplayName("그룹 삭제 성공은 빈 200 응답을 반환한다")
    void deletesGroup() throws Exception {
        mockMvc.perform(delete("/api/v2/groups/{groupId}", 10L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(deleteGroupUseCase).delete(10L);
    }

    @Test
    @DisplayName("초대 코드 가입 결과를 그룹 상세 응답으로 변환한다")
    void joinsGroup() throws Exception {
        when(joinGroupUseCase.join(any())).thenReturn(detail("여행", null, null));

        mockMvc.perform(post("/api/v2/groups/join")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"inviteCode\":\"ab12cd34\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.groupId").value(10))
                .andExpect(jsonPath("$.data.inviteCode").value("AB12CD34"));

        ArgumentCaptor<JoinGroupCommand> captor = ArgumentCaptor.forClass(JoinGroupCommand.class);
        verify(joinGroupUseCase).join(captor.capture());
        assertThat(captor.getValue().inviteCode()).isEqualTo("ab12cd34");
    }

    @Test
    @DisplayName("빈 초대 코드는 INVALID_GROUP_INVITE_CODE로 반환한다")
    void rejectsBlankInviteCodeWithFeatureError() throws Exception {
        when(joinGroupUseCase.join(any())).thenThrow(
                ApplicationException.of(GroupStatusCode.INVALID_GROUP_INVITE_CODE)
        );

        mockMvc.perform(post("/api/v2/groups/join")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"inviteCode\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("INVALID_GROUP_INVITE_CODE"));

        verify(joinGroupUseCase).join(any(JoinGroupCommand.class));
    }

    @Test
    @DisplayName("활성 그룹 멤버 목록을 응답 DTO로 변환한다")
    void getsGroupMembers() throws Exception {
        when(getGroupMembersUseCase.getMembers(10L)).thenReturn(List.of(memberResult()));

        mockMvc.perform(get("/api/v2/groups/{groupId}/members", 10L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].memberId").value(20))
                .andExpect(jsonPath("$.data[0].publicId").value("sua.test"))
                .andExpect(jsonPath("$.data[0].role").value("MEMBER"));
    }

    @Test
    @DisplayName("OWNER 직접 추가 결과를 201로 반환한다")
    void addsGroupMember() throws Exception {
        when(addGroupMemberUseCase.add(any(), any())).thenReturn(memberResult());

        mockMvc.perform(post("/api/v2/groups/{groupId}/members", 10L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"publicId\":\"sua.test\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.memberId").value(20))
                .andExpect(jsonPath("$.data.name").value("최수아"));

        ArgumentCaptor<AddGroupMemberCommand> captor = ArgumentCaptor.forClass(AddGroupMemberCommand.class);
        verify(addGroupMemberUseCase).add(org.mockito.ArgumentMatchers.eq(10L), captor.capture());
        assertThat(captor.getValue().publicId()).isEqualTo("sua.test");
    }

    private GroupDetailResult detail(String name, String memo, String imageUrl) {
        return new GroupDetailResult(
                10L,
                name,
                memo,
                imageUrl,
                1,
                "AB12CD34",
                "https://howmuch.app/groups/join?code=AB12CD34",
                GroupMemberRole.OWNER
        );
    }

    private GroupMemberResult memberResult() {
        return new GroupMemberResult(
                20L,
                "sua.test",
                "최수아",
                GroupMemberRole.MEMBER,
                LocalDateTime.of(2026, 8, 25, 6, 0)
        );
    }
}
