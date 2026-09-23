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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

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
    void requiredHeader_missing_returnsInvalidArgument() throws Exception {
        invalidArgument(mvc.perform(get(TRANSFER)));
        verifyNoInteractions(transfer);
    }

    @Test
    void requiredParameter_missing_returnsInvalidArgument() throws Exception {
        invalidArgument(mvc.perform(get(CANDIDATES)));
        verifyNoInteractions(candidates, importer);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "not-a-uuid"})
    void header_emptyOrMalformed_returnsInvalidArgument(String value) throws Exception {
        invalidArgument(mvc.perform(get(TRANSFER).header("Link-Participant-Context", value)));
        verifyNoInteractions(transfer);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "not-a-number"})
    void parameter_emptyOrMalformed_returnsInvalidArgument(String value) throws Exception {
        invalidArgument(mvc.perform(get(CANDIDATES).param("accountId", value)));
        verifyNoInteractions(candidates, importer);
    }

    @Test
    void optionalParameter_missing_accepted() throws Exception {
        when(candidates.get(SETTLEMENT_ID, 1L, null)).thenReturn(List.of());
        mvc.perform(get(CANDIDATES).param("accountId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isEmpty());
        verify(candidates).get(SETTLEMENT_ID, 1L, null);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "{", "{\"name\":{}}"})
    void requestBody_missingOrMalformed_returnsInvalidArgument(String body) throws Exception {
        invalidArgument(mvc.perform(post("/error-probe/body")
                .contentType(MediaType.APPLICATION_JSON).content(body)));
    }

    @Test
    void requestValidation_fieldDetails_preserved() throws Exception {
        invalidArgument(mvc.perform(post("/error-probe/body")
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\"}")))
                .andExpect(jsonPath("$.error.details.name").value("이름은 필수입니다."));
    }

    @ParameterizedTest
    @ValueSource(strings = {"UNAUTHORIZED", "FORBIDDEN"})
    void authenticationOrAuthorizationError_statusPreserved(String code) throws Exception {
        var expected = CommonStatusCode.valueOf(code);
        error(mvc.perform(get("/error-probe/application").param("code", code)),
                expected.getStatus(), code, expected.getMessage());
    }

    @Test
    void unexpectedException_returnsInternalServerError() throws Exception {
        var expected = CommonStatusCode.INTERNAL_SERVER_ERROR;
        error(mvc.perform(get("/error-probe/unexpected")), 500,
                expected.getCode(), expected.getMessage());
    }

    @Test
    void upload_overLimit_returnsPayloadTooLarge() throws Exception {
        // 업로드 한도 초과가 500이면 클라이언트는 서버가 고장난 줄 안다. 413은 영수증
        // OCR뿐 아니라 그룹·프로필 이미지 업로드에도 함께 적용된다.
        var expected = CommonStatusCode.PAYLOAD_TOO_LARGE;
        error(mvc.perform(get("/error-probe/oversize")), 413,
                expected.getCode(), expected.getMessage());
    }

    @Test
    void multipart_malformed_returnsInvalidArgument() throws Exception {
        invalidArgument(mvc.perform(get("/error-probe/malformed-multipart")));
    }

    @Test
    void multipart_serverFailure_remainsRetryable() throws Exception {
        // 임시 업로드 디렉터리가 꽉 찼거나 쓸 수 없을 때도 컨테이너는 같은 예외로 감싼다.
        // 그걸 400으로 돌려주면 클라이언트는 자기 요청을 고치려 들고 재시도하지 않는다.
        var expected = CommonStatusCode.TEMPORARILY_UNAVAILABLE;
        error(mvc.perform(get("/error-probe/multipart-io")), expected.getStatus(),
                expected.getCode(), expected.getMessage())
                .andExpect(header().string("Retry-After", "1"));
    }

    @Test
    void missingMultipartPartReturnsInvalidArgument() throws Exception {
        // 파트 이름이 다르면 컨트롤러에 들어가기 전에 갈린다. 여기가 400이 아니면
        // 컨트롤러 안의 null 검사는 영원히 실행되지 않고 500만 나간다.
        invalidArgument(mvc.perform(multipart("/error-probe/upload")
                .file(new MockMultipartFile("image", "a.jpg", "image/jpeg", new byte[]{1}))));
    }

    @Test
    void presentMultipartPartStillReachesTheController() throws Exception {
        mvc.perform(multipart("/error-probe/upload")
                        .file(new MockMultipartFile("file", "a.jpg", "image/jpeg", new byte[]{1})))
                .andExpect(status().isOk());
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

        // 실제로는 DispatcherServlet 이 핸들러를 찾기 전에 던진다. 여기서는 매핑만
        // 못 박고, 실제 디스패치 지점은 ReceiptOcrHttpIntegrationTest 가 확인한다.
        @GetMapping("/error-probe/oversize")
        public void oversize() {
            throw new MaxUploadSizeExceededException(5 * 1024 * 1024);
        }

        @GetMapping("/error-probe/malformed-multipart")
        public void malformedMultipart() {
            throw new MultipartException("synthetic malformed multipart");
        }

        @GetMapping("/error-probe/multipart-io")
        public void multipartIoFailure() {
            throw new MultipartException("Failed to parse multipart servlet request",
                    new IOException("No space left on device"));
        }

        @PostMapping("/error-probe/upload")
        public String upload(@RequestPart("file") MultipartFile file) {
            return file.getOriginalFilename();
        }
    }

    record Body(@NotBlank(message = "이름은 필수입니다.") String name) {}
}
