package com.amateur.howmuchbackend.domain.auth.status;

import com.amateur.howmuchbackend.common.exception.StatusCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum AuthStatusCode implements StatusCode {
    INVALID_AUTH_REQUEST("인증 요청값이 올바르지 않습니다.", HttpStatus.BAD_REQUEST),
    INVALID_PHONE_NUMBER("휴대폰 번호 형식이 올바르지 않습니다.", HttpStatus.BAD_REQUEST),
    INVALID_RESIDENT_NUMBER("주민등록번호 앞 7자리가 올바르지 않습니다.", HttpStatus.BAD_REQUEST),
    SMS_RESEND_TOO_SOON("인증번호 재전송은 60초 후에 가능합니다.", HttpStatus.TOO_MANY_REQUESTS),
    SMS_VERIFICATION_EXPIRED("인증번호가 만료되었습니다. 다시 요청해주세요.", HttpStatus.BAD_REQUEST),
    SMS_VERIFICATION_CODE_MISMATCH("인증번호가 일치하지 않습니다.", HttpStatus.BAD_REQUEST),
    SMS_VERIFICATION_ATTEMPTS_EXCEEDED("인증번호 확인 가능 횟수를 초과했습니다.", HttpStatus.TOO_MANY_REQUESTS),
    SMS_SEND_FAILED("인증번호 전송에 실패했습니다. 잠시 후 다시 시도해주세요.", HttpStatus.BAD_GATEWAY),
    AUTH_CHALLENGE_EXPIRED("인증이 만료되었습니다. 다시 인증해주세요.", HttpStatus.BAD_REQUEST),
    INVALID_PIN_FORMAT("PIN은 6자리 숫자여야 합니다.", HttpStatus.BAD_REQUEST),
    PIN_ATTEMPTS_EXCEEDED("PIN 입력 가능 횟수를 초과했습니다.", HttpStatus.TOO_MANY_REQUESTS),
    INVALID_LOGIN_CREDENTIALS("PIN이 올바르지 않습니다.", HttpStatus.UNAUTHORIZED),
    INVALID_TOKEN("토큰이 유효하지 않습니다.", HttpStatus.UNAUTHORIZED),
    USER_NOT_FOUND("사용자를 찾을 수 없습니다.", HttpStatus.NOT_FOUND),
    USER_INACTIVE("비활성화된 사용자입니다.", HttpStatus.FORBIDDEN);

    private final String message;
    private final HttpStatus httpStatus;

    @Override
    public String getCode() {
        return name();
    }
}
