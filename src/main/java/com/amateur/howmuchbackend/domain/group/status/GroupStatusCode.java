package com.amateur.howmuchbackend.domain.group.status;

import com.amateur.howmuchbackend.common.exception.StatusCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum GroupStatusCode implements StatusCode {
    INVALID_GROUP_NAME("그룹 이름은 1자 이상 20자 이하여야 합니다.", 400),
    INVALID_GROUP_MEMO("그룹 메모는 60자 이하여야 합니다.", 400),
    INVALID_GROUP_IMAGE("그룹 이미지는 JPG, PNG, WebP 형식의 5 MiB 이하 이미지여야 합니다.", 400),
    INVALID_GROUP_UPDATE("변경할 그룹 설정이 필요합니다.", 400),
    INVALID_GROUP_INVITE_CODE("그룹 초대 코드는 8자리 영숫자여야 합니다.", 400),
    GROUP_NOT_FOUND("그룹을 찾을 수 없습니다.", 404),
    GROUP_IMAGE_NOT_FOUND("등록된 그룹 이미지를 찾을 수 없습니다.", 404),
    GROUP_ACCESS_DENIED("해당 그룹에 접근할 권한이 없습니다.", 403),
    GROUP_OWNER_REQUIRED("그룹 OWNER 권한이 필요합니다.", 403),
    GROUP_MEMBER_ALREADY_ACTIVE("이미 활성 그룹멤버입니다.", 409),
    GROUP_MEMBER_REMOVED("그룹에서 제거된 사용자는 초대 코드로 재가입할 수 없습니다.", 409),
    GROUP_INVITE_CODE_GENERATION_FAILED("그룹 초대 코드 생성에 실패했습니다.", 500);

    private final String message;
    private final int status;

    @Override
    public String getCode() {
        return name();
    }
}
