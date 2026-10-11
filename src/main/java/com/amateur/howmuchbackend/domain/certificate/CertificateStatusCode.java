package com.amateur.howmuchbackend.domain.certificate;

import com.amateur.howmuchbackend.common.exception.StatusCode;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum CertificateStatusCode implements StatusCode {
    CERTIFICATE_NOT_FOUND("인증서 등록을 찾을 수 없습니다.", 404),
    CERTIFICATE_EXPIRED("등록 또는 QR 시간이 만료되었습니다.", 410),
    CERTIFICATE_CONFLICT("현재 상태에서는 처리할 수 없습니다.", 409),
    CERTIFICATE_INPUT_INVALID("인증서 또는 추가 입력이 올바르지 않습니다.", 400),
    CERTIFICATE_BANK_UNAVAILABLE("인증서 등록을 지원하지 않는 금융사입니다.", 400),
    CERTIFICATE_STORAGE_UNAVAILABLE("인증서 보안 저장소를 사용할 수 없습니다.", 503),
    CERTIFICATE_RATE_LIMITED("잠시 후 다시 시도해주세요.", 429),
    CERTIFICATE_REGISTRATION_UNCERTAIN("금융사 처리 결과를 확인해야 합니다. 중복 등록하지 말고 잠시 후 상태를 확인해주세요.", 409),
    CERTIFICATE_OWNER_VERIFICATION_REQUIRED("금융 명의 확인이 필요합니다.", 409);
    private final String message;
    private final int status;

    public String getCode() {
        return name();
    }
}
