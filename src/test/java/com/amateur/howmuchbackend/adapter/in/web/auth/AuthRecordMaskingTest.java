package com.amateur.howmuchbackend.adapter.in.web.auth;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.ObjectMapper;
import java.lang.reflect.RecordComponent;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Stream;
import static org.assertj.core.api.Assertions.assertThat;

class AuthRecordMaskingTest {
    private static final Set<String> SENSITIVE = Set.of("name", "birthDate", "phoneNumber", "pin", "pinConfirmation",
            "verificationCode", "verificationRequestId", "challengeId", "accessToken", "requestToken",
            "clientIp", "userAgent");
    private final ObjectMapper mapper = new ObjectMapper();

    static Stream<Class<?>> contracts() throws ClassNotFoundException {
        return Stream.of(
                Class.forName("com.amateur.howmuchbackend.adapter.in.web.auth.dto.request.ConfirmSmsVerificationRequest"),
                Class.forName("com.amateur.howmuchbackend.adapter.in.web.auth.dto.request.LoginRequest"),
                Class.forName("com.amateur.howmuchbackend.adapter.in.web.auth.dto.request.RefreshTokenRequest"),
                Class.forName("com.amateur.howmuchbackend.adapter.in.web.auth.dto.request.SendSmsVerificationRequest"),
                Class.forName("com.amateur.howmuchbackend.adapter.in.web.auth.dto.request.SignUpRequest"),
                Class.forName("com.amateur.howmuchbackend.adapter.in.web.auth.dto.response.SmsVerificationConfirmedWebResponse"),
                Class.forName("com.amateur.howmuchbackend.adapter.in.web.auth.dto.response.SmsVerificationSentWebResponse"),
                Class.forName("com.amateur.howmuchbackend.adapter.in.web.auth.dto.response.TokenWebResponse"),
                Class.forName("com.amateur.howmuchbackend.application.port.in.auth.command.ConfirmSmsVerificationCommand"),
                Class.forName("com.amateur.howmuchbackend.application.port.in.auth.command.LoginCommand"),
                Class.forName("com.amateur.howmuchbackend.application.port.in.auth.command.LogoutCommand"),
                Class.forName("com.amateur.howmuchbackend.application.port.in.auth.command.RefreshTokenCommand"),
                Class.forName("com.amateur.howmuchbackend.application.port.in.auth.command.SendSmsVerificationCommand"),
                Class.forName("com.amateur.howmuchbackend.application.port.in.auth.command.SignUpCommand"),
                Class.forName("com.amateur.howmuchbackend.application.port.in.auth.result.SmsVerificationConfirmedResult"),
                Class.forName("com.amateur.howmuchbackend.application.port.in.auth.result.SmsVerificationSentResult"),
                Class.forName("com.amateur.howmuchbackend.application.port.in.auth.result.TokenResult")
        );
    }

    @ParameterizedTest(name = "{0}: masks sensitive fields and preserves safe fields")
    @MethodSource("contracts")
    void authRecord_sensitiveValues_masked(Class<?> type) throws Exception {
        Object record = fixture(type, false);
        String rendered = record.toString();
        assertThat(rendered.startsWith(type.getSimpleName() + "[")).isTrue();
        for (RecordComponent component : type.getRecordComponents()) {
            Object value = component.getAccessor().invoke(record);
            if (SENSITIVE.contains(component.getName())) {
                assertThat(rendered.contains(String.valueOf(value)))
                        .as("%s.%s must not reveal its value", type.getSimpleName(), component.getName()).isFalse();
                assertThat(rendered.contains(component.getName() + "=***")).isTrue();
            } else {
                assertThat(rendered.contains(component.getName() + "=" + value)).isTrue();
            }
        }
    }

    @ParameterizedTest(name = "{0}: masks null values")
    @MethodSource("contracts")
    void authRecord_nullValues_masked(Class<?> type) throws Exception {
        String rendered = fixture(type, true).toString();
        for (RecordComponent component : type.getRecordComponents()) {
            if (SENSITIVE.contains(component.getName())) {
                assertThat(rendered.contains(component.getName() + "=***")).isTrue();
            }
        }
    }

    @ParameterizedTest(name = "{0}: JSON, accessors and record equality remain intact")
    @MethodSource("contracts")
    void authRecord_serializationAndEquality_preserved(Class<?> type) throws Exception {
        Object original = fixture(type, false);
        String json = mapper.writeValueAsString(original);
        Object copy = mapper.readValue(json, type);
        assertThat(original.equals(copy)).isTrue();
        assertThat(original.hashCode() == copy.hashCode()).isTrue();
        var tree = mapper.readTree(json);
        assertThat(tree.size()).isEqualTo(type.getRecordComponents().length);
        for (RecordComponent component : type.getRecordComponents()) {
            Object value = component.getAccessor().invoke(original);
            assertThat(Objects.equals(value, component.getAccessor().invoke(copy)))
                    .as("%s accessor contract", component.getName()).isTrue();
            assertThat(tree.has(component.getName())).isTrue();
            if (value instanceof String) {
                assertThat(tree.get(component.getName()).asString().equals(value)).isTrue();
            }
        }
        if (type.getName().contains(".dto.request.")) {
            Object command = type.getMethod("toCommand").invoke(original);
            for (RecordComponent component : type.getRecordComponents()) {
                assertThat(Objects.equals(component.getAccessor().invoke(original),
                        command.getClass().getMethod(component.getName()).invoke(command))).isTrue();
            }
        } else if (type.getName().contains(".dto.response.")) {
            var from = Arrays.stream(type.getMethods()).filter(m -> m.getName().equals("from")).findFirst().orElseThrow();
            Object result = fixture(from.getParameterTypes()[0], false);
            assertThat(original.equals(from.invoke(null, result))).isTrue();
        }
    }

    private Object fixture(Class<?> type, boolean nullSensitive) throws Exception {
        RecordComponent[] fields = type.getRecordComponents();
        Class<?>[] types = Arrays.stream(fields).map(RecordComponent::getType).toArray(Class<?>[]::new);
        Object[] values = new Object[fields.length];
        for (int i = 0; i < fields.length; i++) {
            var field = fields[i];
            values[i] = field.getType() == boolean.class ? true
                    : field.getType() == LocalDate.class ? LocalDate.of(1995, 3, 14)
                    : field.getType() == LocalDateTime.class ? LocalDateTime.of(2026, 9, 6, 1, 2, 3)
                    : nullSensitive && SENSITIVE.contains(field.getName()) ? null : "synthetic-" + field.getName();
        }
        return type.getDeclaredConstructor(types).newInstance(values);
    }
}
