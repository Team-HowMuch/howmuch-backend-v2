package com.amateur.howmuchbackend.adapter.in.web.user;

import com.amateur.howmuchbackend.application.port.in.user.DeleteUserAsAdminUseCase;
import com.amateur.howmuchbackend.application.port.in.user.SearchAdminUserUseCase;
import com.amateur.howmuchbackend.application.port.in.user.command.SearchAdminUserCommand;
import com.amateur.howmuchbackend.application.port.in.user.result.AdminUserSearchResult;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.common.exception.GlobalExceptionHandler;
import com.amateur.howmuchbackend.common.exception.status.CommonStatusCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AdminUserDeletionControllerTest {
    @Mock private SearchAdminUserUseCase searchUseCase;
    @Mock private DeleteUserAsAdminUseCase deleteUseCase;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mockMvc = MockMvcBuilders.standaloneSetup(new AdminUserDeletionController(searchUseCase, deleteUseCase))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .build();
    }

    @Test
    void searchUsesBodyAndDoesNotReturnPhoneNumber() throws Exception {
        when(searchUseCase.search(any())).thenReturn(new AdminUserSearchResult(2L, "테스트 사용자"));

        mockMvc.perform(post("/api/v2/admin/users/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phoneNumber\":\"010-1234-5678\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userId").value(2))
                .andExpect(jsonPath("$.data.name").value("테스트 사용자"))
                .andExpect(jsonPath("$.data.phoneNumber").doesNotExist());

        ArgumentCaptor<SearchAdminUserCommand> command = ArgumentCaptor.forClass(SearchAdminUserCommand.class);
        verify(searchUseCase).search(command.capture());
        assertThat(command.getValue().phoneNumber()).isEqualTo("010-1234-5678");
        assertThat(command.getValue().toString()).doesNotContain("010-1234-5678");
    }

    @Test
    void blankPhoneIsRejectedBeforeApplicationCall() throws Exception {
        mockMvc.perform(post("/api/v2/admin/users/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phoneNumber\":\" \"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(searchUseCase);
    }

    @Test
    void deleteUsesTargetIdAndReturnsSuccess() throws Exception {
        mockMvc.perform(delete("/api/v2/admin/users/2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
        verify(deleteUseCase).delete(2L);
    }

    @Test
    void forbiddenFromApplicationMapsToHttp403() throws Exception {
        doThrow(ApplicationException.of(CommonStatusCode.FORBIDDEN)).when(deleteUseCase).delete(2L);

        mockMvc.perform(delete("/api/v2/admin/users/2"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }
}
