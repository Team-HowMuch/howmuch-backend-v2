package com.amateur.howmuchbackend.application.user.service;

import com.amateur.howmuchbackend.application.auth.model.CurrentActor;
import com.amateur.howmuchbackend.application.port.in.user.command.UpdateMyProfileCommand;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.application.port.out.user.LoadUserPort;
import com.amateur.howmuchbackend.application.port.out.user.ProfileImageStoragePort;
import com.amateur.howmuchbackend.application.port.out.user.SaveUserPort;
import com.amateur.howmuchbackend.application.user.policy.ProfileImagePolicy;
import com.amateur.howmuchbackend.application.user.policy.PublicIdPolicy;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.user.User;
import com.amateur.howmuchbackend.domain.user.UserRole;
import com.amateur.howmuchbackend.domain.user.status.UserStatusCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("프로필 공개 ID 변경")
class UpdateMyProfileServiceTest {
    private static final Long USER_ID = 1L;
    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 8, 22, 9, 0);

    @Mock
    private CurrentActorPort currentActorPort;

    @Mock
    private LoadUserPort loadUserPort;

    @Mock
    private SaveUserPort saveUserPort;

    @Mock
    private ProfileImageStoragePort profileImageStoragePort;

    private UpdateMyProfileService updateMyProfileService;

    @BeforeEach
    void setUp() {
        updateMyProfileService = new UpdateMyProfileService(
                currentActorPort,
                loadUserPort,
                saveUserPort,
                profileImageStoragePort,
                new PublicIdPolicy(),
                new ProfileImagePolicy()
        );
        when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(USER_ID));
        when(loadUserPort.findByIdForUpdate(USER_ID)).thenReturn(Optional.of(user()));
    }

    @Test
    @DisplayName("공개 ID를 소문자로 정규화하고 잠근 사용자 상태에 저장한다")
    void publicId_update_normalizedAndSaved() {
        when(loadUserPort.existsByPublicId("new.id_1")).thenReturn(false);
        when(saveUserPort.update(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var result = updateMyProfileService.update(new UpdateMyProfileCommand("  New.ID_1  ", null, false));

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(saveUserPort).update(userCaptor.capture());
        assertThat(userCaptor.getValue().publicId()).isEqualTo("new.id_1");
        assertThat(result.publicId()).isEqualTo("new.id_1");
        verify(loadUserPort).findByIdForUpdate(USER_ID);
    }

    @Test
    @DisplayName("다른 사용자가 쓰는 공개 ID는 저장하지 않고 409 오류로 거부한다")
    void rejectsDuplicatePublicId() {
        when(loadUserPort.existsByPublicId("taken.id")).thenReturn(true);

        assertThatThrownBy(() -> updateMyProfileService.update(
                new UpdateMyProfileCommand("taken.id", null, false)
        )).isInstanceOfSatisfying(ApplicationException.class, exception ->
                assertThat(exception.getStatusCode()).isEqualTo(UserStatusCode.PUBLIC_ID_ALREADY_EXISTS));

        verify(saveUserPort, never()).update(any(User.class));
        verify(profileImageStoragePort, never()).save(any());
    }

    @Test
    @DisplayName("정규화한 공개 ID와 아바타가 그대로이면 중복 조회와 저장을 생략한다")
    void skipsUnchangedProfile() {
        var result = updateMyProfileService.update(new UpdateMyProfileCommand(" CURRENT.ID ", null, false));

        assertThat(result.publicId()).isEqualTo("current.id");
        verify(loadUserPort, never()).existsByPublicId(any());
        verify(saveUserPort, never()).update(any(User.class));
    }

    private User user() {
        return new User(
                USER_ID,
                "identity-key",
                "최수아",
                "current.id",
                null,
                null,
                "01012345678",
                "encoded-pin",
                null,
                null,
                true,
                UserRole.USER,
                true,
                null,
                CREATED_AT,
                null,
                CREATED_AT,
                CREATED_AT
        );
    }
}
