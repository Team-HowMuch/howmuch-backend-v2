package com.amateur.howmuchbackend.adapter.in.web.auth;

import com.amateur.howmuchbackend.application.auth.policy.IdentityPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "howmuch.auth.sms.discord.webhook-url=http://127.0.0.1:9/unused",
        "howmuch.auth.sms.resend-cooldown=5m",
        "howmuch.settlement.notification-outbox.enabled=false"
})
@DisplayName("인증 API 계약 통합")
class AuthSignupContractIntegrationTest {
    private static final String PHONE_NUMBER_CHECK_PATH = "/api/v2/auth/phone-number/check";
    private static final String ADDRESS_KEY_PREFIX = "auth:phone-number-check:address:";
    private static final String PHONE_KEY_PREFIX = "auth:phone-number-check:phone:";
    private static final AtomicInteger PHONE_SUFFIX = new AtomicInteger(
            ThreadLocalRandom.current().nextInt(100_000_000)
    );

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private StringRedisTemplate redis;
    @Autowired private IdentityPolicy identityPolicy;

    @Test
    @DisplayName("인증 없이 활성 계정의 휴대폰 번호 가입 여부를 확인한다")
    void phoneRegistration_activeUserWithoutAuth_returnsTrue() throws Exception {
        String phoneNumber = randomPhoneNumber();
        String clientAddress = randomClientAddress();
        Long userId = insertUser(phoneNumber, true);
        try {
            mockMvc.perform(phoneNumberCheckRequest(phoneNumber, clientAddress))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.registered").value(true));
        } finally {
            deleteUser(userId);
            clearCheckLimits(clientAddress, List.of(phoneNumber));
        }
    }

    @Test
    @DisplayName("비활성 계정과 가입되지 않은 휴대폰 번호는 false를 반환한다")
    void phoneRegistration_inactiveOrMissingUser_returnsFalse() throws Exception {
        String inactivePhoneNumber = randomPhoneNumber();
        String missingPhoneNumber = randomPhoneNumber();
        String clientAddress = randomClientAddress();
        Long userId = insertUser(inactivePhoneNumber, false);
        try {
            mockMvc.perform(phoneNumberCheckRequest(inactivePhoneNumber, clientAddress))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.registered").value(false));

            mockMvc.perform(phoneNumberCheckRequest(missingPhoneNumber, clientAddress))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.registered").value(false));
        } finally {
            deleteUser(userId);
            clearCheckLimits(clientAddress, List.of(inactivePhoneNumber, missingPhoneNumber));
        }
    }

    @Test
    @DisplayName("공백 또는 잘못된 휴대폰 번호는 전용 형식 오류로 거절한다")
    void phoneNumber_blankOrInvalid_returnsFormatError() throws Exception {
        String clientAddress = randomClientAddress();
        try {
            mockMvc.perform(post(PHONE_NUMBER_CHECK_PATH)
                            .with(request -> {
                                request.setRemoteAddr(clientAddress);
                                return request;
                            })
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("INVALID_PHONE_NUMBER"));

            for (String invalidPhoneNumber : List.of("", "010-12")) {
                mockMvc.perform(phoneNumberCheckRequest(invalidPhoneNumber, clientAddress))
                        .andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.error.code").value("INVALID_PHONE_NUMBER"));
            }
        } finally {
            redis.delete(ADDRESS_KEY_PREFIX + clientAddress);
        }
    }

    @Test
    @DisplayName("같은 휴대폰 번호의 반복 확인은 번호 quota로 제한한다")
    void limitsRepeatedPhoneNumberChecks() throws Exception {
        String phoneNumber = randomPhoneNumber();
        String firstAddress = randomClientAddress();
        String secondAddress = randomClientAddress();
        try {
            mockMvc.perform(phoneNumberCheckRequest(phoneNumber, firstAddress))
                    .andExpect(status().isOk());

            mockMvc.perform(phoneNumberCheckRequest(phoneNumber, secondAddress))
                    .andExpect(status().isTooManyRequests())
                    .andExpect(jsonPath("$.error.code").value("TOO_MANY_REQUEST"));
        } finally {
            clearCheckLimits(firstAddress, List.of(phoneNumber));
            redis.delete(ADDRESS_KEY_PREFIX + secondAddress);
        }
    }

    @Test
    @DisplayName("같은 IP의 61번째 확인은 IP quota로 제한한다")
    void limitsAddressAfterSixtyChecks() throws Exception {
        String clientAddress = randomClientAddress();
        List<String> phoneNumbers = new ArrayList<>();
        try {
            for (int index = 0; index < 60; index++) {
                String phoneNumber = randomPhoneNumber();
                phoneNumbers.add(phoneNumber);
                mockMvc.perform(phoneNumberCheckRequest(phoneNumber, clientAddress))
                        .andExpect(status().isOk());
            }

            String rejectedPhoneNumber = randomPhoneNumber();
            phoneNumbers.add(rejectedPhoneNumber);
            mockMvc.perform(phoneNumberCheckRequest(rejectedPhoneNumber, clientAddress))
                    .andExpect(status().isTooManyRequests())
                    .andExpect(jsonPath("$.error.code").value("TOO_MANY_REQUEST"));
        } finally {
            clearCheckLimits(clientAddress, phoneNumbers);
        }
    }

    @Test
    @DisplayName("OpenAPI에 휴대폰 번호 가입 여부 확인 API를 공개한다")
    void documentsPhoneNumberRegistrationCheck() throws Exception {
        byte[] content = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsByteArray();
        JsonNode document = objectMapper.readTree(new String(content, StandardCharsets.UTF_8));
        JsonNode operation = document.path("paths").path(PHONE_NUMBER_CHECK_PATH).path("post");
        JsonNode requestSchema = document.path("components")
                .path("schemas")
                .path("PhoneNumberRegistrationCheckRequest");

        assertThat(operation.isMissingNode()).isFalse();
        assertThat(operation.path("responses").has("200")).isTrue();
        assertThat(operation.path("responses").has("400")).isTrue();
        assertThat(operation.path("responses").has("429")).isTrue();
        assertThat(operation.path("responses").has("503")).isTrue();
        assertThat(requestSchema.path("required").toString()).contains("\"phoneNumber\"");
    }

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

    private MockHttpServletRequestBuilder phoneNumberCheckRequest(String phoneNumber, String clientAddress) {
        return post(PHONE_NUMBER_CHECK_PATH)
                .with(request -> {
                    request.setRemoteAddr(clientAddress);
                    return request;
                })
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"phoneNumber":"%s"}
                        """.formatted(phoneNumber));
    }

    private Long insertUser(String phoneNumber, boolean active) {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        return jdbc.queryForObject("""
                INSERT INTO users(identity_key, name, public_id, phone_number, pin_hash,
                    phone_verified, role, active, created_at, updated_at)
                VALUES (?, '가입 확인', ?, ?, 'not-used', true, 'USER', ?, ?, ?) RETURNING id
                """, Long.class, suffix, "check_" + suffix.substring(0, 12), phoneNumber, active, now, now);
    }

    private void deleteUser(Long userId) {
        jdbc.update("DELETE FROM users WHERE id=?", userId);
    }

    private void clearCheckLimits(String clientAddress, List<String> phoneNumbers) {
        List<String> keys = new ArrayList<>();
        keys.add(ADDRESS_KEY_PREFIX + clientAddress);
        phoneNumbers.stream()
                .map(identityPolicy::phoneHash)
                .map(PHONE_KEY_PREFIX::concat)
                .forEach(keys::add);
        redis.delete(keys);
    }

    private String randomPhoneNumber() {
        int suffix = Math.floorMod(PHONE_SUFFIX.getAndIncrement(), 100_000_000);
        return "010%08d".formatted(suffix);
    }

    private String randomClientAddress() {
        return "2001:db8:" + UUID.randomUUID();
    }
}
