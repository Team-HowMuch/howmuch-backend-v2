package com.amateur.howmuchbackend.adapter.in.web.user;

import com.amateur.howmuchbackend.application.port.in.user.GetMyProfileImageUseCase;
import com.amateur.howmuchbackend.application.port.in.user.GetMyProfileUseCase;
import com.amateur.howmuchbackend.application.port.in.user.UpdateMyProfileUseCase;
import com.amateur.howmuchbackend.application.port.in.user.command.UpdateMyProfileCommand;
import com.amateur.howmuchbackend.application.port.in.user.result.MyProfileResult;
import com.amateur.howmuchbackend.domain.user.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@DisplayName("사용자 프로필 Web API 계약")
class UserControllerTest {
    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 8, 22, 9, 0);

    @Mock
    private GetMyProfileUseCase getMyProfileUseCase;

    @Mock
    private GetMyProfileImageUseCase getMyProfileImageUseCase;

    @Mock
    private UpdateMyProfileUseCase updateMyProfileUseCase;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        UserController controller = new UserController(
                getMyProfileUseCase,
                getMyProfileImageUseCase,
                updateMyProfileUseCase
        );
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    @DisplayName("내 프로필은 nickname 없이 공개 ID를 반환한다")
    void returnsPublicIdInMyProfile() throws Exception {
        when(getMyProfileUseCase.getMyProfile()).thenReturn(result("current.id"));

        mockMvc.perform(get("/api/v2/user/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.publicId").value("current.id"))
                .andExpect(jsonPath("$.data.nickname").doesNotExist());
    }

    @Test
    @DisplayName("multipart 공개 ID 입력을 Application Command로 전달하고 변경 결과를 반환한다")
    void updatesPublicId() throws Exception {
        when(updateMyProfileUseCase.update(any(UpdateMyProfileCommand.class)))
                .thenReturn(result("new.id_1"));

        mockMvc.perform(multipart("/api/v2/user/me/profile")
                        .param("publicId", "New.ID_1")
                        .with(request -> {
                            request.setMethod("PATCH");
                            return request;
                        }))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.publicId").value("new.id_1"));

        ArgumentCaptor<UpdateMyProfileCommand> commandCaptor =
                ArgumentCaptor.forClass(UpdateMyProfileCommand.class);
        verify(updateMyProfileUseCase).update(commandCaptor.capture());
        assertThat(commandCaptor.getValue().publicId()).isEqualTo("New.ID_1");
    }

    private MyProfileResult result(String publicId) {
        return new MyProfileResult(
                1L,
                "최수아",
                publicId,
                null,
                null,
                "01012345678",
                false,
                true,
                UserRole.USER,
                null,
                CREATED_AT
        );
    }
}
