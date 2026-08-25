package com.amateur.howmuchbackend.adapter.in.web.group;

import com.amateur.howmuchbackend.adapter.in.web.common.ApiResponse;
import com.amateur.howmuchbackend.adapter.in.web.group.docs.GroupDocs;
import com.amateur.howmuchbackend.adapter.in.web.group.dto.request.CreateGroupWebRequest;
import com.amateur.howmuchbackend.adapter.in.web.group.dto.request.AddGroupMemberWebRequest;
import com.amateur.howmuchbackend.adapter.in.web.group.dto.request.JoinGroupWebRequest;
import com.amateur.howmuchbackend.adapter.in.web.group.dto.request.UpdateGroupWebRequest;
import com.amateur.howmuchbackend.adapter.in.web.group.dto.response.GroupDetailWebResponse;
import com.amateur.howmuchbackend.adapter.in.web.group.dto.response.GroupSummaryWebResponse;
import com.amateur.howmuchbackend.adapter.in.web.group.dto.response.GroupMemberWebResponse;
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
import lombok.RequiredArgsConstructor;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v2/groups")
public class GroupController implements GroupDocs {
    private final CreateGroupUseCase createGroupUseCase;
    private final GetMyGroupsUseCase getMyGroupsUseCase;
    private final GetGroupUseCase getGroupUseCase;
    private final GetGroupImageUseCase getGroupImageUseCase;
    private final UpdateGroupUseCase updateGroupUseCase;
    private final DeleteGroupUseCase deleteGroupUseCase;
    private final JoinGroupUseCase joinGroupUseCase;
    private final GetGroupMembersUseCase getGroupMembersUseCase;
    private final AddGroupMemberUseCase addGroupMemberUseCase;

    @Override
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<GroupDetailWebResponse> createGroup(
            @ModelAttribute CreateGroupWebRequest request
    ) {
        return ApiResponse.created(GroupDetailWebResponse.from(
                createGroupUseCase.create(request.toCommand())
        ));
    }

    @Override
    @GetMapping
    public ApiResponse<List<GroupSummaryWebResponse>> getMyGroups() {
        return ApiResponse.success(getMyGroupsUseCase.getMyGroups()
                .stream()
                .map(GroupSummaryWebResponse::from)
                .toList());
    }

    @Override
    @GetMapping("/{groupId}")
    public ApiResponse<GroupDetailWebResponse> getGroup(@PathVariable Long groupId) {
        return ApiResponse.success(GroupDetailWebResponse.from(getGroupUseCase.get(groupId)));
    }

    @Override
    @GetMapping("/{groupId}/image")
    public ResponseEntity<byte[]> getGroupImage(@PathVariable Long groupId) {
        GroupImage image = getGroupImageUseCase.getImage(groupId);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.parseMediaType(image.contentType()))
                .body(image.content());
    }

    @Override
    @PatchMapping(value = "/{groupId}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<GroupDetailWebResponse> updateGroup(
            @PathVariable Long groupId,
            @ModelAttribute UpdateGroupWebRequest request
    ) {
        return ApiResponse.success(GroupDetailWebResponse.from(
                updateGroupUseCase.update(groupId, request.toCommand())
        ));
    }

    @Override
    @DeleteMapping("/{groupId}")
    public ApiResponse<Void> deleteGroup(@PathVariable Long groupId) {
        deleteGroupUseCase.delete(groupId);
        return ApiResponse.success();
    }

    @Override
    @PostMapping("/join")
    public ApiResponse<GroupDetailWebResponse> joinGroup(
            @Valid @RequestBody JoinGroupWebRequest request
    ) {
        return ApiResponse.success(GroupDetailWebResponse.from(
                joinGroupUseCase.join(request.toCommand())
        ));
    }

    @Override
    @GetMapping("/{groupId}/members")
    public ApiResponse<List<GroupMemberWebResponse>> getGroupMembers(@PathVariable Long groupId) {
        return ApiResponse.success(getGroupMembersUseCase.getMembers(groupId)
                .stream()
                .map(GroupMemberWebResponse::from)
                .toList());
    }

    @Override
    @PostMapping("/{groupId}/members")
    public ApiResponse<GroupMemberWebResponse> addGroupMember(
            @PathVariable Long groupId,
            @Valid @RequestBody AddGroupMemberWebRequest request
    ) {
        return ApiResponse.created(GroupMemberWebResponse.from(
                addGroupMemberUseCase.add(groupId, request.toCommand())
        ));
    }
}
