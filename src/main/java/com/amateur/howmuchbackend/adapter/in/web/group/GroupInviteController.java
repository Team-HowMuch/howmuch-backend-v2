package com.amateur.howmuchbackend.adapter.in.web.group;

import com.amateur.howmuchbackend.adapter.in.web.common.ApiResponse;
import com.amateur.howmuchbackend.adapter.in.web.group.docs.GroupInviteDocs;
import com.amateur.howmuchbackend.adapter.in.web.group.dto.response.GroupInviteWebResponse;
import com.amateur.howmuchbackend.application.port.in.group.GetGroupInviteUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v2/group-invites")
public class GroupInviteController implements GroupInviteDocs {
    private final GetGroupInviteUseCase getGroupInviteUseCase;

    @Override
    @GetMapping("/{code}")
    public ApiResponse<GroupInviteWebResponse> get(@PathVariable String code) {
        return ApiResponse.success(GroupInviteWebResponse.from(getGroupInviteUseCase.get(code)));
    }
}
