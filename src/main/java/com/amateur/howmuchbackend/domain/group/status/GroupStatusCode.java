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
    GROUP_NOT_FOUND("그룹을 찾을 수 없습니다.", 404),
    GROUP_IMAGE_NOT_FOUND("등록된 그룹 이미지를 찾을 수 없습니다.", 404),
    GROUP_ACCESS_DENIED("해당 그룹에 접근할 권한이 없습니다.", 403),
    GROUP_OWNER_REQUIRED("그룹 OWNER 권한이 필요합니다.", 403),
    GROUP_INVITE_CODE_GENERATION_FAILED("그룹 초대 코드 생성에 실패했습니다.", 500);

    private final String message;
    private final int status;

    @Override
    public String getCode() {
        return name();
    }
}
