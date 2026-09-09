package com.amateur.howmuchbackend.common.exception;

import com.amateur.howmuchbackend.adapter.in.web.settlement.SettlementPaymentExpenseController;
import com.amateur.howmuchbackend.adapter.in.web.settlement.SettlementTransferInstructionController;
import com.amateur.howmuchbackend.application.port.in.settlement.GetSettlementPaymentExpenseCandidatesUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.GetSettlementTransferInstructionUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.ImportSettlementPaymentExpenseUseCase;
import com.amateur.howmuchbackend.common.exception.status.CommonStatusCode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class GlobalExceptionHandlerTest {
    private static final UUID SETTLEMENT_ID = UUID.fromString("44c9323d-6a65-4a93-aeaa-ef8f24341c8a");
    private static final String CANDIDATES = "/api/v2/settlements/" + SETTLEMENT_ID
            + "/expense-candidates/payment-history";
    private static final String TRANSFER = "/api/v2/settlement-links/" + "x".repeat(43)
            + "/transfer-instruction";
    private final GetSettlementPaymentExpenseCandidatesUseCase candidates = mock(GetSettlementPaymentExpenseCandidatesUseCase.class);
    private final ImportSettlementPaymentExpenseUseCase importer = mock(ImportSettlementPaymentExpenseUseCase.class);
    private final GetSettlementTransferInstructionUseCase transfer = mock(GetSettlementTransferInstructionUseCase.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(
                new SettlementPaymentExpenseController(candidates, importer),
                new SettlementTransferInstructionController(transfer),
                new ErrorProbeController()
        ).setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    void missingRequiredHeaderReturnsInvalidArgument() throws Exception {
        invalidArgument(mvc.perform(get(TRANSFER)));
        verifyNoInteractions(transfer);
    }

    @Test
    void missingRequiredParameterReturnsInvalidArgument() throws Exception {
        invalidArgument(mvc.perform(get(CANDIDATES)));
        verifyNoInteractions(candidates, importer);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "not-a-uuid"})
    void emptyOrMalformedHeaderReturnsInvalidArgument(String value) throws Exception {
        invalidArgument(mvc.perform(get(TRANSFER).header("Link-Participant-Context", value)));
        verifyNoInteractions(transfer);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "not-a-number"})
    void emptyOrMalformedParameterReturnsInvalidArgument(String value) throws Exception {
        invalidArgument(mvc.perform(get(CANDIDATES).param("accountId", value)));
        verifyNoInteractions(candidates, importer);
    }

    @Test
    void optionalParameterCanStillBeOmitted() throws Exception {
        when(candidates.get(SETTLEMENT_ID, 1L, null)).thenReturn(List.of());
        mvc.perform(get(CANDIDATES).param("accountId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isEmpty());
        verify(candidates).get(SETTLEMENT_ID, 1L, null);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "{", "{\"name\":{}}"})
    void missingOrMalformedBodyPreservesInvalidArgument(String body) throws Exception {
        invalidArgument(mvc.perform(post("/error-probe/body")
                .contentType(MediaType.APPLICATION_JSON).content(body)));
    }

    @Test
    void validationPreservesFieldDetails() throws Exception {
        invalidArgument(mvc.perform(post("/error-probe/body")
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\"}")))
                .andExpect(jsonPath("$.error.details.name").value("이름은 필수입니다."));
    }

    @ParameterizedTest
    @ValueSource(strings = {"UNAUTHORIZED", "FORBIDDEN"})
    void applicationAuthenticationAndAuthorizationErrorsArePreserved(String code) throws Exception {
        var expected = CommonStatusCode.valueOf(code);
        error(mvc.perform(get("/error-probe/application").param("code", code)),
                expected.getStatus(), code, expected.getMessage());
    }

    @Test
    void unexpectedFailureRemainsInternalServerError() throws Exception {
        var expected = CommonStatusCode.INTERNAL_SERVER_ERROR;
        error(mvc.perform(get("/error-probe/unexpected")), 500,
                expected.getCode(), expected.getMessage());
    }

    private ResultActions invalidArgument(ResultActions result) throws Exception {
        return error(result, 400, "INVALID_ARGUMENT", "요청값이 올바르지 않습니다.");
    }

    private ResultActions error(ResultActions result, int expectedStatus, String code, String message) throws Exception {
        return result.andExpect(status().is(expectedStatus))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.error.status").value(expectedStatus))
                .andExpect(jsonPath("$.error.code").value(code))
                .andExpect(jsonPath("$.error.message").value(message))
                .andExpect(jsonPath("$.error.timestamp").isNotEmpty());
    }

    @RestController
    static class ErrorProbeController {
        @PostMapping("/error-probe/body")
        public Body body(@Valid @RequestBody Body body) {
            return body;
        }

        @GetMapping("/error-probe/application")
        public void application(@RequestParam("code") CommonStatusCode code) {
            throw ApplicationException.of(code);
        }

        @GetMapping("/error-probe/unexpected")
        public void unexpected() {
            throw new IllegalStateException("synthetic failure");
        }
    }

    record Body(@NotBlank(message = "이름은 필수입니다.") String name) {}
}
