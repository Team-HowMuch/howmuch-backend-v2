package com.amateur.howmuchbackend.adapter.in.web.settlement;

import com.amateur.howmuchbackend.application.port.in.settlement.ChangeMySettlementCategoryUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.command.ChangeMySettlementCategoryCommand;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementCategoryResult;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.common.exception.GlobalExceptionHandler;
import com.amateur.howmuchbackend.domain.settlement.status.SettlementStatusCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@DisplayName("내 카테고리 수정 Web API 계약")
class SettlementMyCategoryControllerTest {
    private static final UUID SETTLEMENT_ID = UUID.fromString("5f0c7f43-1f43-4a55-9d0e-6a8f5d1f2b11");
    private static final String PATH = "/api/v2/settlements/{settlementId}/my-category";

    @Mock private ChangeMySettlementCategoryUseCase changeMyCategoryUseCase;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mockMvc = MockMvcBuilders.standaloneSetup(new SettlementMyCategoryController(changeMyCategoryUseCase))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .build();
    }

    @Test
    @DisplayName("저장된 내 카테고리를 목록·상세의 myCategory와 같은 모양(code, name, icon)으로 돌려준다")
    void changeMyCategory_returnsSavedCategory() throws Exception {
        when(changeMyCategoryUseCase.change(eq(SETTLEMENT_ID), eq(new ChangeMySettlementCategoryCommand("FOOD"))))
                .thenReturn(new SettlementCategoryResult("FOOD", "식비", "식당, 배달", "food"));

        mockMvc.perform(put(PATH, SETTLEMENT_ID)
                        .contentType("application/json")
                        .content("{\"categoryCode\":\"FOOD\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.code").value("FOOD"))
                .andExpect(jsonPath("$.data.name").value("식비"))
                .andExpect(jsonPath("$.data.icon").value("food"))
                .andExpect(jsonPath("$.data.description").doesNotExist());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"categoryCode\":\"\"}", "{\"categoryCode\":\"  \"}",
            "{\"categoryCode\":\"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA\"}"})
    @DisplayName("코드가 없거나 비었거나 50자를 넘으면 서비스를 부르지 않고 INVALID_ARGUMENT로 거부한다")
    void changeMyCategory_invalidBody_rejected(String body) throws Exception {
        mockMvc.perform(put(PATH, SETTLEMENT_ID).contentType("application/json").content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("INVALID_ARGUMENT"));
        verifyNoInteractions(changeMyCategoryUseCase);
    }

    @Test
    @DisplayName("형식이 틀린 정산 ID나 깨진 JSON도 서비스를 부르지 않고 INVALID_ARGUMENT로 거부한다")
    void changeMyCategory_malformedRequest_rejected() throws Exception {
        mockMvc.perform(put(PATH, "not-a-uuid").contentType("application/json").content("{\"categoryCode\":\"FOOD\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_ARGUMENT"));
        mockMvc.perform(put(PATH, SETTLEMENT_ID).contentType("application/json").content("{\"categoryCode\":"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_ARGUMENT"));
        verifyNoInteractions(changeMyCategoryUseCase);
    }

    @Test
    @DisplayName("목록에 없는 코드는 INVALID_SETTLEMENT_CATEGORY 400으로 내려간다")
    void changeMyCategory_unknownCode_badRequest() throws Exception {
        when(changeMyCategoryUseCase.change(eq(SETTLEMENT_ID), eq(new ChangeMySettlementCategoryCommand("TRANSFER"))))
                .thenThrow(ApplicationException.of(SettlementStatusCode.INVALID_SETTLEMENT_CATEGORY));

        mockMvc.perform(put(PATH, SETTLEMENT_ID)
                        .contentType("application/json")
                        .content("{\"categoryCode\":\"TRANSFER\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_SETTLEMENT_CATEGORY"));
    }
}
