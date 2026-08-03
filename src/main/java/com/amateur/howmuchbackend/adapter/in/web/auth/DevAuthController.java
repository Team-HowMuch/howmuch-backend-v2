package com.amateur.howmuchbackend.adapter.in.web.auth;

import com.amateur.howmuchbackend.adapter.in.web.common.ApiResponse;
import com.amateur.howmuchbackend.adapter.in.web.auth.dto.request.DevLoginRequest;
import com.amateur.howmuchbackend.application.port.in.auth.DevLoginUseCase;
import com.amateur.howmuchbackend.application.port.in.auth.response.TokenResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("dev")
@RequiredArgsConstructor
@RequestMapping("/api/v2/auth/dev")
@Tag(name = "개발용 인증 API", description = "dev 프로필에서만 제공되는 인증 API")
public class DevAuthController {
    private final DevLoginUseCase devLoginUseCase;

    @PostMapping("/login")
    @Operation(summary = "개발용 로그인", description = "SMS challenge 없이 기존 계정으로 로그인합니다.")
    public ApiResponse<TokenResponse> login(@Valid @RequestBody DevLoginRequest request) {
        return ApiResponse.success(devLoginUseCase.login(request.toRequest()));
    }
}
