package com.amateur.howmuchbackend.adapter.in.web.auth.docs;

import com.amateur.howmuchbackend.adapter.in.web.common.ApiResponse;
import com.amateur.howmuchbackend.adapter.in.web.auth.dto.request.ConfirmSmsVerificationRequest;
import com.amateur.howmuchbackend.adapter.in.web.auth.dto.request.LoginRequest;
import com.amateur.howmuchbackend.adapter.in.web.auth.dto.request.RefreshTokenRequest;
import com.amateur.howmuchbackend.adapter.in.web.auth.dto.request.SendSmsVerificationRequest;
import com.amateur.howmuchbackend.adapter.in.web.auth.dto.request.SignUpRequest;
import com.amateur.howmuchbackend.application.port.in.auth.response.SmsVerificationConfirmedResponse;
import com.amateur.howmuchbackend.application.port.in.auth.response.SmsVerificationSentResponse;
import com.amateur.howmuchbackend.application.port.in.auth.response.TokenResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;


@Tag(name = "인증 API", description = "인증 관련 API")
public interface AuthDocs {

    @Operation(summary = "sms 인증번호 전송", description = "사용자에게 SMS 인증번호를 전송합니다.")
    ApiResponse<SmsVerificationSentResponse> sendSms(SendSmsVerificationRequest request);

    @Operation(summary = "sms 인증번호 확인", description = "사용자가 입력한 SMS 인증번호를 확인합니다.")
    ApiResponse<SmsVerificationConfirmedResponse> confirmSms(ConfirmSmsVerificationRequest request);

    @Operation(summary = "회원가입", description = "SMS 인증 challenge로 회원가입을 완료합니다. 가입 후 토큰은 발급하지 않습니다.")
    ApiResponse<Void> signUp(SignUpRequest request);

    @Operation(summary = "로그인", description = "휴대폰 번호와 PIN으로 로그인합니다. 새 로그인은 기존 기기 세션을 만료시킵니다.")
    ApiResponse<TokenResponse> login(LoginRequest request);

    @Operation(summary = "토큰 갱신", description = "만료된 토큰을 갱신합니다.")
    ApiResponse<TokenResponse> refresh(RefreshTokenRequest request);

    @Operation(
            summary = "로그아웃",
            description = "사용자를 로그아웃 처리합니다.",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    ApiResponse<Void> logout(@Parameter(hidden = true) jakarta.servlet.http.HttpServletRequest request);

}
