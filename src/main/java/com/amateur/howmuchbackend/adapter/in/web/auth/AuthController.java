package com.amateur.howmuchbackend.adapter.in.web.auth;

import com.amateur.howmuchbackend.adapter.in.web.auth.docs.AuthDocs;
import com.amateur.howmuchbackend.adapter.in.web.auth.dto.request.CompleteAuthRequest;
import com.amateur.howmuchbackend.adapter.in.web.auth.dto.request.ConfirmSmsVerificationRequest;
import com.amateur.howmuchbackend.adapter.in.web.auth.dto.request.RefreshTokenRequest;
import com.amateur.howmuchbackend.adapter.in.web.auth.dto.request.SendSmsVerificationRequest;
import com.amateur.howmuchbackend.adapter.in.web.common.ApiResponse;
import com.amateur.howmuchbackend.application.port.in.auth.CompleteAuthUseCase;
import com.amateur.howmuchbackend.application.port.in.auth.ConfirmSmsVerificationUseCase;
import com.amateur.howmuchbackend.application.port.in.auth.LogoutUseCase;
import com.amateur.howmuchbackend.application.port.in.auth.RefreshTokenUseCase;
import com.amateur.howmuchbackend.application.port.in.auth.SendSmsVerificationUseCase;
import com.amateur.howmuchbackend.application.port.in.auth.response.SmsVerificationConfirmedResponse;
import com.amateur.howmuchbackend.application.port.in.auth.response.SmsVerificationSentResponse;
import com.amateur.howmuchbackend.application.port.in.auth.response.TokenResponse;
import jakarta.validation.Valid;
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
    private final CompleteAuthUseCase completeAuthUseCase;
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

    @PostMapping("/complete")
    public ApiResponse<TokenResponse> complete(
            @Valid @RequestBody CompleteAuthRequest request
    ) {
        return ApiResponse.success(completeAuthUseCase.complete(request.toRequest()));
    }

    @PostMapping("/token/refresh")
    public ApiResponse<TokenResponse> refresh(
            @Valid @RequestBody RefreshTokenRequest request
    ) {
        return ApiResponse.success(refreshTokenUseCase.refresh(request.toRequest()));
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout() {
        logoutUseCase.logout();
        return ApiResponse.success();
    }
}
