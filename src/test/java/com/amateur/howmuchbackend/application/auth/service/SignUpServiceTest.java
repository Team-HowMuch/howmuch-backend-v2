package com.amateur.howmuchbackend.application.auth.service;

import com.amateur.howmuchbackend.application.auth.policy.IdentityPolicy;
import com.amateur.howmuchbackend.application.auth.policy.PinPolicy;
import com.amateur.howmuchbackend.application.port.in.auth.command.SignUpCommand;
import com.amateur.howmuchbackend.application.port.out.auth.AuthChallengeStorePort;
import com.amateur.howmuchbackend.application.port.out.user.LoadUserPort;
import com.amateur.howmuchbackend.application.port.out.user.SaveUserPort;
import com.amateur.howmuchbackend.application.user.policy.PublicIdPolicy;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.auth.challenge.AuthChallenge;
import com.amateur.howmuchbackend.domain.user.User;
import com.amateur.howmuchbackend.domain.user.status.UserStatusCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("회원가입 공개 ID 생성")
class SignUpServiceTest {
    private static final String CHALLENGE_ID = "signup-challenge";
    private static final String PHONE_NUMBER = "01012345678";
    private static final String COLLIDING_PUBLIC_ID = "user_aaaaaaaaaaaa";
    private static final String AVAILABLE_PUBLIC_ID = "user_bbbbbbbbbbbb";

    @Mock
    private PublicIdPolicy publicIdPolicy;

    @Mock
    private AuthChallengeStorePort authChallengeStorePort;

    @Mock
    private LoadUserPort loadUserPort;

    @Mock
    private SaveUserPort saveUserPort;

    @Mock
    private PasswordEncoder passwordEncoder;

    private SignUpService signUpService;

    @BeforeEach
    void setUp() {
        signUpService = new SignUpService(
                new PinPolicy(5, Duration.ofMinutes(30)),
                new IdentityPolicy("test-identity-hmac-secret"),
                publicIdPolicy,
                authChallengeStorePort,
                loadUserPort,
                saveUserPort,
                passwordEncoder
        );
    }

    @Test
    @DisplayName("충돌한 후보를 건너뛰고 사용 가능한 공개 ID로 가입한다")
    void retriesCollidingPublicId() {
        prepareValidSignUp();
        when(publicIdPolicy.generate()).thenReturn(COLLIDING_PUBLIC_ID, AVAILABLE_PUBLIC_ID);
        when(loadUserPort.existsByPublicId(COLLIDING_PUBLIC_ID)).thenReturn(true);
        when(loadUserPort.existsByPublicId(AVAILABLE_PUBLIC_ID)).thenReturn(false);
        when(passwordEncoder.encode("135790")).thenReturn("encoded-pin");
        when(saveUserPort.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        signUpService.signUp(command());

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(saveUserPort).save(userCaptor.capture());
        assertThat(userCaptor.getValue().name()).isEqualTo("최수아");
        assertThat(userCaptor.getValue().publicId()).isEqualTo(AVAILABLE_PUBLIC_ID);
        assertThat(userCaptor.getValue().phoneNumber()).isEqualTo(PHONE_NUMBER);
        verify(publicIdPolicy, times(2)).generate();
        verify(authChallengeStorePort).deleteById(CHALLENGE_ID);
    }

    @Test
    @DisplayName("다섯 후보가 모두 충돌하면 사용자를 저장하지 않는다")
    void rejectsWhenAllCandidatesCollide() {
        prepareValidSignUp();
        when(publicIdPolicy.generate()).thenReturn(COLLIDING_PUBLIC_ID);
        when(loadUserPort.existsByPublicId(COLLIDING_PUBLIC_ID)).thenReturn(true);

        assertThatThrownBy(() -> signUpService.signUp(command()))
                .isInstanceOfSatisfying(ApplicationException.class, exception ->
                        assertThat(exception.getStatusCode())
                                .isEqualTo(UserStatusCode.PUBLIC_ID_GENERATION_FAILED));
        verify(publicIdPolicy, times(5)).generate();
        verify(saveUserPort, never()).save(any(User.class));
        verify(authChallengeStorePort, never()).deleteById(CHALLENGE_ID);
    }

    private void prepareValidSignUp() {
        AuthChallenge challenge = new AuthChallenge(
                CHALLENGE_ID,
                PHONE_NUMBER,
                LocalDateTime.now(ZoneOffset.UTC).plusMinutes(10)
        );
        when(authChallengeStorePort.findById(CHALLENGE_ID)).thenReturn(Optional.of(challenge));
        when(loadUserPort.findByPhoneNumber(PHONE_NUMBER)).thenReturn(Optional.empty());
    }

    private SignUpCommand command() {
        return new SignUpCommand(CHALLENGE_ID, "최수아", "135790", "135790", true, false);
    }
}
