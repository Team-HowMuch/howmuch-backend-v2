package com.amateur.howmuchbackend.adapter.in.web.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "howmuch.auth.sms.discord.webhook-url=http://127.0.0.1:9/unused",
        "howmuch.settlement.notification-outbox.enabled=false"
})
@DisplayName("회원가입 생년월일 계약 통합")
class AuthSignupContractIntegrationTest {
    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @Test
    @DisplayName("생년월일이 없으면 회원가입 요청을 거부한다")
    void requiresBirthDate() throws Exception {
        mockMvc.perform(post("/api/v2/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "challengeId": "challenge-id",
                                  "name": "홍길동",
                                  "pin": "135790",
                                  "pinConfirmation": "135790",
                                  "requiredTermsAgreed": true,
                                  "marketingAgreed": false
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_ARGUMENT"))
                .andExpect(jsonPath("$.error.details.birthDate").value("생년월일은 필수입니다."));
    }

    @Test
    @DisplayName("유효하지 않은 달력 날짜를 회원가입 요청에서 거부한다")
    void rejectsInvalidCalendarDate() throws Exception {
        mockMvc.perform(post("/api/v2/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "challengeId": "challenge-id",
                                  "name": "홍길동",
                                  "birthDate": "1995-02-30",
                                  "pin": "135790",
                                  "pinConfirmation": "135790",
                                  "requiredTermsAgreed": true,
                                  "marketingAgreed": false
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_ARGUMENT"));
    }

    @Test
    @DisplayName("OpenAPI에 생년월일을 required date 필드로 공개한다")
    void documentsBirthDateAsRequiredDate() throws Exception {
        byte[] content = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsByteArray();
        JsonNode document = objectMapper.readTree(new String(content, StandardCharsets.UTF_8));
        JsonNode schema = document.path("components").path("schemas").path("SignUpRequest");

        assertThat(schema.path("properties").path("birthDate").path("type").stringValue())
                .isEqualTo("string");
        assertThat(schema.path("properties").path("birthDate").path("format").stringValue())
                .isEqualTo("date");
        assertThat(schema.path("required").toString()).contains("\"birthDate\"");
    }
}
