package com.amateur.howmuchbackend.adapter.in.web.group.docs;

import com.amateur.howmuchbackend.adapter.in.web.common.ApiResponse;
import com.amateur.howmuchbackend.adapter.in.web.group.dto.response.GroupInviteWebResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "공개 그룹 초대 API", description = "비로그인 그룹 초대 미리보기")
public interface GroupInviteDocs {
    @Operation(summary = "그룹 초대 미리보기", description = "인증 없이 그룹명, 현재 방장 이름, 활성 멤버 수를 "
            + "data에 반환하며 가입은 처리하지 않습니다. inviterName은 실제 공유자가 아닌 현재 방장입니다. "
            + "이미지는 멤버 인증이 필요하여 제외합니다. IP당 60초에 60회까지 조회할 수 있습니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "미리보기 조회 성공", useReturnTypeSchema = true),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404", description = "GROUP_NOT_FOUND - 형식 오류·없는 코드·삭제 그룹·비활성 방장"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "429", description = "TOO_MANY_REQUEST - 조회 한도 초과, Retry-After: 60"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "503", description = "TEMPORARILY_UNAVAILABLE - 조회 저장소 일시 장애")
    })
    ApiResponse<GroupInviteWebResponse> get(
            @Parameter(description = "8자리 영숫자 초대 코드. 앞뒤 공백 제거 후 대문자로 정규화합니다.") String code
    );
}
