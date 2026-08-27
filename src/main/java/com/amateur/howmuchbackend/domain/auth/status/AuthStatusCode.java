package com.amateur.howmuchbackend.domain.auth.status;

import com.amateur.howmuchbackend.common.exception.StatusCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum AuthStatusCode implements StatusCode {
    INVALID_AUTH_REQUEST("인증 요청값이 올바르지 않습니다.", 400),
    INVALID_PHONE_NUMBER("휴대폰 번호 형식이 올바르지 않습니다.", 400),
    SMS_RESEND_TOO_SOON("인증번호 재전송은 60초 후에 가능합니다.", 429),
    SMS_VERIFICATION_EXPIRED("인증번호가 만료되었습니다. 다시 요청해주세요.", 400),
    SMS_VERIFICATION_CODE_MISMATCH("인증번호가 일치하지 않습니다.", 400),
    SMS_VERIFICATION_ATTEMPTS_EXCEEDED("인증번호 확인 가능 횟수를 초과했습니다.", 429),
    SMS_SEND_FAILED("인증번호 전송에 실패했습니다. 잠시 후 다시 시도해주세요.", 502),
    AUTH_CHALLENGE_EXPIRED("인증이 만료되었습니다. 다시 인증해주세요.", 400),
    INVALID_PIN_FORMAT("PIN은 사용할 수 없는 6자리 숫자입니다.", 400),
    PIN_CONFIRMATION_MISMATCH("PIN 확인 값이 일치하지 않습니다.", 400),
    REQUIRED_TERMS_NOT_AGREED("필수 약관에 동의해야 합니다.", 400),
    PHONE_NUMBER_ALREADY_REGISTERED("이미 가입된 휴대폰 번호입니다.", 409),
    PIN_ATTEMPTS_EXCEEDED("PIN 입력 가능 횟수를 초과했습니다. 잠시 후 다시 시도해주세요.", 429),
    INVALID_LOGIN_CREDENTIALS("휴대폰 번호 또는 PIN이 올바르지 않습니다.", 401),
    INVALID_TOKEN("토큰이 유효하지 않습니다.", 401),
    USER_NOT_FOUND("사용자를 찾을 수 없습니다.", 404),
    USER_INACTIVE("비활성화된 사용자입니다.", 403);

    private final String message;
    private final int status;

    @Override
    public String getCode() {
        return name();
    }
}
