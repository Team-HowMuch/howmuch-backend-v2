package com.amateur.howmuchbackend.infra.auth.config;

import com.amateur.howmuchbackend.application.auth.policy.AuthSessionPolicy;
import com.amateur.howmuchbackend.application.auth.policy.IdentityPolicy;
import com.amateur.howmuchbackend.application.auth.policy.PinPolicy;
import com.amateur.howmuchbackend.application.auth.policy.SmsVerificationPolicy;
import com.amateur.howmuchbackend.application.user.policy.NicknamePolicy;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({
        IdentityProperties.class,
        SmsVerificationProperties.class,
        PinProperties.class,
        AuthSessionProperties.class
})
public class AuthPolicyConfig {
    @Bean
    IdentityPolicy identityPolicy(IdentityProperties properties) {
        return new IdentityPolicy(properties.hmacSecret());
    }

    @Bean
    SmsVerificationPolicy smsVerificationPolicy(SmsVerificationProperties properties) {
        return new SmsVerificationPolicy(
                properties.otpTtl(),
                properties.challengeTtl(),
                properties.resendCooldown(),
                properties.maxAttempts()
        );
    }

    @Bean
    PinPolicy pinPolicy(PinProperties properties) {
        return new PinPolicy(properties.maxAttempts());
    }

    @Bean
    AuthSessionPolicy authSessionPolicy(AuthSessionProperties properties) {
        return new AuthSessionPolicy(
                properties.singleSessionPerUser(),
                properties.rotateRequestToken()
        );
    }

    @Bean
    NicknamePolicy nicknamePolicy() {
        return new NicknamePolicy();
    }
}
