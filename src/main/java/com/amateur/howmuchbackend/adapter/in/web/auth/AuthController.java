package com.amateur.howmuchbackend.adapter.in.web.auth;

import com.amateur.howmuchbackend.adapter.in.web.auth.docs.AuthDocs;
import com.amateur.howmuchbackend.adapter.in.web.auth.dto.request.ConfirmSmsVerificationRequest;
import com.amateur.howmuchbackend.adapter.in.web.auth.dto.request.LoginRequest;
import com.amateur.howmuchbackend.adapter.in.web.auth.dto.request.PhoneNumberRegistrationCheckRequest;
import com.amateur.howmuchbackend.adapter.in.web.auth.dto.request.RefreshTokenRequest;
import com.amateur.howmuchbackend.adapter.in.web.auth.dto.request.SendSmsVerificationRequest;
import com.amateur.howmuchbackend.adapter.in.web.auth.dto.request.SignUpRequest;
import com.amateur.howmuchbackend.adapter.in.web.auth.dto.response.PhoneNumberRegistrationWebResponse;
import com.amateur.howmuchbackend.adapter.in.web.auth.dto.response.SmsVerificationConfirmedWebResponse;
import com.amateur.howmuchbackend.adapter.in.web.auth.dto.response.SmsVerificationSentWebResponse;
import com.amateur.howmuchbackend.adapter.in.web.auth.dto.response.TokenWebResponse;
import com.amateur.howmuchbackend.adapter.in.web.common.ApiResponse;
import com.amateur.howmuchbackend.application.port.in.auth.CheckPhoneNumberRegistrationUseCase;
import com.amateur.howmuchbackend.application.port.in.auth.ConfirmSmsVerificationUseCase;
import com.amateur.howmuchbackend.application.port.in.auth.LoginUseCase;
import com.amateur.howmuchbackend.application.port.in.auth.LogoutUseCase;
import com.amateur.howmuchbackend.application.port.in.auth.RefreshTokenUseCase;
import com.amateur.howmuchbackend.application.port.in.auth.SendSmsVerificationUseCase;
import com.amateur.howmuchbackend.application.port.in.auth.SignUpUseCase;
import com.amateur.howmuchbackend.application.port.in.auth.command.LogoutCommand;
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
    private final CheckPhoneNumberRegistrationUseCase checkPhoneNumberRegistrationUseCase;
    private final SendSmsVerificationUseCase sendSmsVerificationUseCase;
    private final ConfirmSmsVerificationUseCase confirmSmsVerificationUseCase;
    private final SignUpUseCase signUpUseCase;
    private final LoginUseCase loginUseCase;
    private final RefreshTokenUseCase refreshTokenUseCase;
    private final LogoutUseCase logoutUseCase;

    @PostMapping("/phone-number/check")
    public ApiResponse<PhoneNumberRegistrationWebResponse> checkPhoneNumber(
            @Valid @RequestBody PhoneNumberRegistrationCheckRequest request,
            HttpServletRequest servletRequest
    ) {
        return ApiResponse.success(PhoneNumberRegistrationWebResponse.from(
                checkPhoneNumberRegistrationUseCase.check(request.toCommand(servletRequest.getRemoteAddr()))
        ));
    }

    @PostMapping("/sms/send")
    public ApiResponse<SmsVerificationSentWebResponse> sendSms(
            @Valid @RequestBody SendSmsVerificationRequest request
    ) {
        return ApiResponse.success(SmsVerificationSentWebResponse.from(sendSmsVerificationUseCase.send(request.toCommand())));
    }

    @PostMapping("/sms/confirm")
    public ApiResponse<SmsVerificationConfirmedWebResponse> confirmSms(
            @Valid @RequestBody ConfirmSmsVerificationRequest request
    ) {
        return ApiResponse.success(SmsVerificationConfirmedWebResponse.from(confirmSmsVerificationUseCase.confirm(request.toCommand())));
    }

    @PostMapping("/signup")
    public ApiResponse<Void> signUp(
            @Valid @RequestBody SignUpRequest request
    ) {
        signUpUseCase.signUp(request.toCommand());
        return ApiResponse.success();
    }

    @PostMapping("/login")
    public ApiResponse<TokenWebResponse> login(
            @Valid @RequestBody LoginRequest request
    ) {
        return ApiResponse.success(TokenWebResponse.from(loginUseCase.login(request.toCommand())));
    }

    @PostMapping("/token/refresh")
    public ApiResponse<TokenWebResponse> refresh(
            @Valid @RequestBody RefreshTokenRequest request
    ) {
        return ApiResponse.success(TokenWebResponse.from(refreshTokenUseCase.refresh(request.toCommand())));
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(HttpServletRequest request) {
        logoutUseCase.logout(new LogoutCommand(request.getRemoteAddr(), request.getHeader("User-Agent")));
        return ApiResponse.success();
    }
}
