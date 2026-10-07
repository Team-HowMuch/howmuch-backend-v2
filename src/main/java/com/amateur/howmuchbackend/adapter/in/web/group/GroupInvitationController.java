package com.amateur.howmuchbackend.adapter.in.web.group;

import com.amateur.howmuchbackend.adapter.in.web.common.ApiResponse;
import com.amateur.howmuchbackend.adapter.in.web.group.docs.GroupInvitationDocs;
import com.amateur.howmuchbackend.adapter.in.web.group.dto.request.CreateGroupInvitationWebRequest;
import com.amateur.howmuchbackend.adapter.in.web.group.dto.response.GroupInvitationWebResponse;
import com.amateur.howmuchbackend.application.port.in.group.*;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@Validated
@RequiredArgsConstructor
@RequestMapping("/api/v2")
public class GroupInvitationController implements GroupInvitationDocs {
    private final CreateGroupInvitationUseCase createInvitation;
    private final GetGroupInvitationUseCase getInvitation;
    private final AcceptGroupInvitationUseCase acceptInvitation;
    private final DeclineGroupInvitationUseCase declineInvitation;

    @Override @PostMapping("/groups/{groupId}/invitations")
    public ApiResponse<GroupInvitationWebResponse> create(@PathVariable Long groupId,
                                                         @RequestBody CreateGroupInvitationWebRequest request) {
        return ApiResponse.created(GroupInvitationWebResponse.from(createInvitation.create(groupId, request.toCommand())));
    }
    @Override @GetMapping("/group-invitations/{invitationId}")
    public ApiResponse<GroupInvitationWebResponse> get(@PathVariable UUID invitationId) {
        return ApiResponse.success(GroupInvitationWebResponse.from(getInvitation.get(invitationId)));
    }
    @Override @PostMapping("/group-invitations/{invitationId}/accept")
    public ApiResponse<GroupInvitationWebResponse> accept(@PathVariable UUID invitationId) {
        return ApiResponse.success(GroupInvitationWebResponse.from(acceptInvitation.accept(invitationId)));
    }
    @Override @PostMapping("/group-invitations/{invitationId}/decline")
    public ApiResponse<GroupInvitationWebResponse> decline(@PathVariable UUID invitationId) {
        return ApiResponse.success(GroupInvitationWebResponse.from(declineInvitation.decline(invitationId)));
    }
}
