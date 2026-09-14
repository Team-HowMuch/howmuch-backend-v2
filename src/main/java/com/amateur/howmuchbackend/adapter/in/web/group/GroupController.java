package com.amateur.howmuchbackend.adapter.in.web.group;

import com.amateur.howmuchbackend.adapter.in.web.common.ApiResponse;
import com.amateur.howmuchbackend.adapter.in.web.common.ImageWebResponse;
import com.amateur.howmuchbackend.adapter.in.web.group.docs.GroupDocs;
import com.amateur.howmuchbackend.adapter.in.web.group.dto.request.CreateGroupWebRequest;
import com.amateur.howmuchbackend.adapter.in.web.group.dto.request.AddGroupMemberWebRequest;
import com.amateur.howmuchbackend.adapter.in.web.group.dto.request.JoinGroupWebRequest;
import com.amateur.howmuchbackend.adapter.in.web.group.dto.request.UpdateGroupWebRequest;
import com.amateur.howmuchbackend.adapter.in.web.group.dto.request.TransferGroupOwnershipWebRequest;
import com.amateur.howmuchbackend.adapter.in.web.group.dto.response.GroupDetailWebResponse;
import com.amateur.howmuchbackend.adapter.in.web.group.dto.response.GroupSummaryWebResponse;
import com.amateur.howmuchbackend.adapter.in.web.group.dto.response.GroupMemberWebResponse;
import com.amateur.howmuchbackend.application.group.model.GroupImage;
import com.amateur.howmuchbackend.application.user.model.ProfileImage;
import com.amateur.howmuchbackend.application.port.in.group.CreateGroupUseCase;
import com.amateur.howmuchbackend.application.port.in.group.AddGroupMemberUseCase;
import com.amateur.howmuchbackend.application.port.in.group.DeleteGroupUseCase;
import com.amateur.howmuchbackend.application.port.in.group.GetGroupImageUseCase;
import com.amateur.howmuchbackend.application.port.in.group.GetGroupUseCase;
import com.amateur.howmuchbackend.application.port.in.group.GetMyGroupsUseCase;
import com.amateur.howmuchbackend.application.port.in.group.GetGroupMemberAvatarUseCase;
import com.amateur.howmuchbackend.application.port.in.group.GetGroupMembersUseCase;
import com.amateur.howmuchbackend.application.port.in.group.JoinGroupUseCase;
import com.amateur.howmuchbackend.application.port.in.group.UpdateGroupUseCase;
import com.amateur.howmuchbackend.application.port.in.group.TransferGroupOwnershipUseCase;
import com.amateur.howmuchbackend.application.port.in.group.LeaveGroupUseCase;
import com.amateur.howmuchbackend.application.port.in.group.RemoveGroupMemberUseCase;
import lombok.RequiredArgsConstructor;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
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
    private final GetGroupMemberAvatarUseCase getGroupMemberAvatarUseCase;
    private final AddGroupMemberUseCase addGroupMemberUseCase;
    private final TransferGroupOwnershipUseCase transferGroupOwnershipUseCase;
    private final LeaveGroupUseCase leaveGroupUseCase;
    private final RemoveGroupMemberUseCase removeGroupMemberUseCase;

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
        return ImageWebResponse.ok(image.contentType(), image.content());
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
                .map(result -> GroupMemberWebResponse.from(groupId, result))
                .toList());
    }

    @Override
    @GetMapping("/{groupId}/members/{memberId}/avatar")
    public ResponseEntity<byte[]> getGroupMemberAvatar(
            @PathVariable Long groupId,
            @PathVariable Long memberId
    ) {
        ProfileImage image = getGroupMemberAvatarUseCase.getMemberAvatar(groupId, memberId);
        return ImageWebResponse.ok(image.contentType(), image.content());
    }

    @Override
    @PostMapping("/{groupId}/members")
    public ApiResponse<GroupMemberWebResponse> addGroupMember(
            @PathVariable Long groupId,
            @Valid @RequestBody AddGroupMemberWebRequest request
    ) {
        return ApiResponse.created(GroupMemberWebResponse.from(
                groupId,
                addGroupMemberUseCase.add(groupId, request.toCommand())
        ));
    }

    @Override
    @PutMapping("/{groupId}/owner")
    public ApiResponse<Void> transferGroupOwnership(
            @PathVariable Long groupId,
            @Valid @RequestBody TransferGroupOwnershipWebRequest request
    ) {
        transferGroupOwnershipUseCase.transfer(groupId, request.toCommand());
        return ApiResponse.success();
    }

    @Override
    @DeleteMapping("/{groupId}/members/me")
    public ApiResponse<Void> leaveGroup(@PathVariable Long groupId) {
        leaveGroupUseCase.leave(groupId);
        return ApiResponse.success();
    }

    @Override
    @DeleteMapping("/{groupId}/members/{memberId}")
    public ApiResponse<Void> removeGroupMember(
            @PathVariable Long groupId,
            @PathVariable Long memberId
    ) {
        removeGroupMemberUseCase.remove(groupId, memberId);
        return ApiResponse.success();
    }
}
