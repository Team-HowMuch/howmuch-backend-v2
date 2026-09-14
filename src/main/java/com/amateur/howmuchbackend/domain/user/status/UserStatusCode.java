package com.amateur.howmuchbackend.domain.user.status;

import com.amateur.howmuchbackend.common.exception.StatusCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum UserStatusCode implements StatusCode {
    INVALID_PUBLIC_ID("공개 ID는 영문 소문자, 숫자, 점, 밑줄로 구성된 4~20자로 입력해주세요.", 400),
    INVALID_PUBLIC_ID_SEARCH_PREFIX("공개 ID 검색어는 영문, 숫자, 점, 밑줄로 구성된 1~20자로 입력해주세요.", 400),
    PUBLIC_ID_ALREADY_EXISTS("이미 사용 중인 공개 ID입니다.", 409),
    PUBLIC_ID_GENERATION_FAILED("공개 ID 생성에 실패했습니다. 다시 시도해주세요.", 500),
    PUSH_TARGET_REGISTRATION_CONFLICT("앱 푸시 대상 등록이 충돌했습니다. 다시 시도해주세요.", 409),
    PROFILE_IMAGE_NOT_FOUND("등록된 아바타를 찾을 수 없습니다.", 404),
    USER_DELETION_PIN_MISMATCH("탈퇴 PIN이 일치하지 않습니다.", 401),
    USER_DELETION_BLOCKED_BY_SETTLEMENT("미완료 정산이 있어 탈퇴할 수 없습니다.", 409),
    USER_DELETION_GROUP_OWNER_TRANSFER_REQUIRED("그룹 OWNER 권한을 이전한 뒤 탈퇴할 수 있습니다.", 409);

    private final String message;
    private final int status;

    @Override
    public String getCode() {
        return name();
    }
}
