package com.amateur.howmuchbackend.adapter.in.web.auth;

import com.amateur.howmuchbackend.adapter.in.web.auth.docs.AuthDocs;
import com.amateur.howmuchbackend.adapter.in.web.auth.dto.request.ConfirmSmsVerificationRequest;
import com.amateur.howmuchbackend.adapter.in.web.auth.dto.request.LoginRequest;
import com.amateur.howmuchbackend.adapter.in.web.auth.dto.request.RefreshTokenRequest;
import com.amateur.howmuchbackend.adapter.in.web.auth.dto.request.SendSmsVerificationRequest;
import com.amateur.howmuchbackend.adapter.in.web.auth.dto.request.SignUpRequest;
import com.amateur.howmuchbackend.adapter.in.web.common.ApiResponse;
import com.amateur.howmuchbackend.application.port.in.auth.ConfirmSmsVerificationUseCase;
import com.amateur.howmuchbackend.application.port.in.auth.LoginUseCase;
import com.amateur.howmuchbackend.application.port.in.auth.LogoutUseCase;
import com.amateur.howmuchbackend.application.port.in.auth.RefreshTokenUseCase;
import com.amateur.howmuchbackend.application.port.in.auth.SendSmsVerificationUseCase;
import com.amateur.howmuchbackend.application.port.in.auth.SignUpUseCase;
import com.amateur.howmuchbackend.application.port.in.auth.request.LogoutRequest;
import com.amateur.howmuchbackend.application.port.in.auth.response.SmsVerificationConfirmedResponse;
import com.amateur.howmuchbackend.application.port.in.auth.response.SmsVerificationSentResponse;
import com.amateur.howmuchbackend.application.port.in.auth.response.TokenResponse;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v2/auth")
public class AuthController implements AuthDocs {
    private final SendSmsVerificationUseCase sendSmsVerificationUseCase;
    private final ConfirmSmsVerificationUseCase confirmSmsVerificationUseCase;
    private final SignUpUseCase signUpUseCase;
    private final LoginUseCase loginUseCase;
    private final RefreshTokenUseCase refreshTokenUseCase;
    private final LogoutUseCase logoutUseCase;

    @PostMapping("/sms/send")
    public ApiResponse<SmsVerificationSentResponse> sendSms(
            @Valid @RequestBody SendSmsVerificationRequest request
    ) {
        return ApiResponse.success(sendSmsVerificationUseCase.send(request.toRequest()));
    }

    @PostMapping("/sms/confirm")
    public ApiResponse<SmsVerificationConfirmedResponse> confirmSms(
            @Valid @RequestBody ConfirmSmsVerificationRequest request
    ) {
        return ApiResponse.success(confirmSmsVerificationUseCase.confirm(request.toRequest()));
    }

    @PostMapping("/signup")
    public ApiResponse<Void> signUp(
            @Valid @RequestBody SignUpRequest request
    ) {
        signUpUseCase.signUp(request.toRequest());
        return ApiResponse.success();
    }

    @PostMapping("/login")
    public ApiResponse<TokenResponse> login(
            @Valid @RequestBody LoginRequest request
    ) {
        return ApiResponse.success(loginUseCase.login(request.toRequest()));
    }

    @PostMapping("/token/refresh")
    public ApiResponse<TokenResponse> refresh(
            @Valid @RequestBody RefreshTokenRequest request
    ) {
        return ApiResponse.success(refreshTokenUseCase.refresh(request.toRequest()));
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(HttpServletRequest request) {
        logoutUseCase.logout(new LogoutRequest(request.getRemoteAddr(), request.getHeader("User-Agent")));
        return ApiResponse.success();
    }
}
