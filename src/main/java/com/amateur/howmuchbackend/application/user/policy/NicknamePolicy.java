package com.amateur.howmuchbackend.application.user.policy;

import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.common.exception.status.CommonStatusCode;

import java.util.regex.Pattern;

public class NicknamePolicy {
    private static final Pattern NICKNAME_PATTERN = Pattern.compile("^[가-힣A-Za-z0-9]{2,20}$");

    public String normalize(String nickname) {
        if (nickname == null) {
            throw invalidNickname();
        }

        String normalized = nickname.trim();
        if (!NICKNAME_PATTERN.matcher(normalized).matches()) {
            throw invalidNickname();
        }
        return normalized;
    }

    private ApplicationException invalidNickname() {
        return ApplicationException.of(
                CommonStatusCode.INVALID_ARGUMENT,
                "닉네임은 공백과 특수문자 없이 한글, 영문, 숫자 2~20자로 입력해주세요."
        );
    }
}
