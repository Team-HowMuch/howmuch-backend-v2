package com.amateur.howmuchbackend.adapter.in.web.auth.docs;

import com.amateur.howmuchbackend.adapter.in.web.common.ApiResponse;
import com.amateur.howmuchbackend.adapter.in.web.auth.dto.request.ConfirmSmsVerificationRequest;
import com.amateur.howmuchbackend.adapter.in.web.auth.dto.request.LoginRequest;
import com.amateur.howmuchbackend.adapter.in.web.auth.dto.request.RefreshTokenRequest;
import com.amateur.howmuchbackend.adapter.in.web.auth.dto.request.SendSmsVerificationRequest;
import com.amateur.howmuchbackend.adapter.in.web.auth.dto.request.SignUpRequest;
import com.amateur.howmuchbackend.adapter.in.web.auth.dto.response.SmsVerificationConfirmedWebResponse;
import com.amateur.howmuchbackend.adapter.in.web.auth.dto.response.SmsVerificationSentWebResponse;
import com.amateur.howmuchbackend.adapter.in.web.auth.dto.response.TokenWebResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;


@Tag(name = "인증 API", description = "인증 관련 API")
public interface AuthDocs {

    @Operation(summary = "sms 인증번호 전송", description = "사용자에게 SMS 인증번호를 전송합니다. 재발송 대기 시간에는 요청이 차단되며, 인증 실패 한도 소진·인증 완료·인증번호 만료·발송 오류로 대기 시간이 해제되지 않습니다.")
    ApiResponse<SmsVerificationSentWebResponse> sendSms(SendSmsVerificationRequest request);

    @Operation(summary = "sms 인증번호 확인", description = "SMS 인증번호를 한 번만 소비하여 challenge를 발급합니다. 실패 허용 횟수에 도달하면 해당 인증번호는 차단됩니다. 인증 성공·차단 후에도 기존 재발송 대기 시간이 유지됩니다.")
    ApiResponse<SmsVerificationConfirmedWebResponse> confirmSms(ConfirmSmsVerificationRequest request);

    @Operation(
            summary = "회원가입",
            description = "SMS 인증 challenge로 회원가입을 완료하고 공개 ID를 자동 생성합니다. 가입 후 토큰은 발급하지 않습니다."
    )
    ApiResponse<Void> signUp(SignUpRequest request);

    @Operation(summary = "로그인", description = "휴대폰 번호와 PIN으로 로그인합니다. 동시 요청의 진행 중 시도도 제한 횟수에 포함하며, 한도 도달 시 잠금 기간 후 재시도할 수 있습니다. 로그인 성공 시 시도 횟수를 초기화합니다. 새 로그인은 기존 기기 세션을 만료시킵니다.")
    ApiResponse<TokenWebResponse> login(LoginRequest request);

    @Operation(summary = "토큰 갱신", description = "유효한 Refresh Token으로 토큰을 갱신합니다. 토큰 회전 시 로그아웃·새 로그인·다른 갱신으로 기존 세션이 변경되면 INVALID_TOKEN으로 실패합니다.")
    ApiResponse<TokenWebResponse> refresh(RefreshTokenRequest request);

    @Operation(
            summary = "로그아웃",
            description = "사용자를 로그아웃 처리합니다.",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    ApiResponse<Void> logout(@Parameter(hidden = true) jakarta.servlet.http.HttpServletRequest request);

}
