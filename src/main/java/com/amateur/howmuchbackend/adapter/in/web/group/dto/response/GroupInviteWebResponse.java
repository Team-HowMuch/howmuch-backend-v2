package com.amateur.howmuchbackend.adapter.in.web.group.dto.response;

import com.amateur.howmuchbackend.application.port.in.group.result.GroupInviteResult;
import io.swagger.v3.oas.annotations.media.Schema;

public record GroupInviteWebResponse(
        @Schema(description = "그룹 이름", example = "여행 모임") String groupName,
        @Schema(description = "현재 방장 이름. 실제 링크 공유자와 다를 수 있습니다.", example = "김하늘")
        String inviterName,
        @Schema(description = "방장을 포함한 활성 멤버 수", example = "2") int memberCount
) {
    public static GroupInviteWebResponse from(GroupInviteResult result) {
        return new GroupInviteWebResponse(result.groupName(), result.inviterName(), result.memberCount());
    }
}
