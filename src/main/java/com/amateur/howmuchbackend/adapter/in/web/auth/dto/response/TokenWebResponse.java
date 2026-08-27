package com.amateur.howmuchbackend.adapter.in.web.auth.dto.response;

import com.amateur.howmuchbackend.application.port.in.auth.result.TokenResult;

public record TokenWebResponse(
        String accessToken,
        String requestToken
) {
    public static TokenWebResponse from(TokenResult result) {
        return new TokenWebResponse(result.accessToken(), result.requestToken());
    }
}
