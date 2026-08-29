package com.amateur.howmuchbackend.application.user.service;

import com.amateur.howmuchbackend.application.auth.model.CurrentActor;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.application.port.out.user.UserPushTargetStorePort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.auth.status.AuthStatusCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("내 앱 푸시 대상 관리")
class ManageMyPushTargetServiceTest {
    private static final Long USER_ID = 1L;

    @Mock private CurrentActorPort currentActorPort;
    @Mock private UserPushTargetStorePort pushTargetStorePort;

    private ManageMyPushTargetService service;

    @BeforeEach
    void setUp() {
        service = new ManageMyPushTargetService(
                currentActorPort,
                pushTargetStorePort
        );
    }

    @Test
    @DisplayName("Firebase installation ID를 정규화해 현재 사용자에 저장한다")
    void registersPushTarget() {
        givenCurrentActor();
        when(pushTargetStorePort.claim(
                eq(USER_ID),
                eq("firebase-installation-id"),
                any(LocalDateTime.class)
        )).thenReturn(true);

        service.register("  firebase-installation-id  ");

        verify(pushTargetStorePort).claim(
                eq(USER_ID),
                eq("firebase-installation-id"),
                any(LocalDateTime.class)
        );
    }

    @Test
    @DisplayName("등록된 푸시 대상을 해제한다")
    void clearsPushTarget() {
        givenCurrentActor();
        when(pushTargetStorePort.clear(eq(USER_ID), any(LocalDateTime.class))).thenReturn(true);

        service.clear();

        verify(pushTargetStorePort).clear(eq(USER_ID), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("현재 사용자를 찾지 못하면 등록을 거부한다")
    void rejectsMissingUser() {
        givenCurrentActor();
        when(pushTargetStorePort.claim(eq(USER_ID), eq("target"), any(LocalDateTime.class)))
                .thenReturn(false);

        assertThatThrownBy(() -> service.register("target"))
                .isInstanceOf(ApplicationException.class)
                .satisfies(exception -> {
                    ApplicationException applicationException = (ApplicationException) exception;
                    org.assertj.core.api.Assertions.assertThat(applicationException.getStatusCode())
                            .isEqualTo(AuthStatusCode.USER_NOT_FOUND);
                });
    }

    @Test
    @DisplayName("빈 Firebase installation ID를 거부한다")
    void rejectsBlankTarget() {
        assertThatThrownBy(() -> service.register("  "))
                .isInstanceOf(ApplicationException.class);

        verify(pushTargetStorePort, org.mockito.Mockito.never())
                .claim(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), any());
    }

    private void givenCurrentActor() {
        when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(USER_ID));
    }
}
