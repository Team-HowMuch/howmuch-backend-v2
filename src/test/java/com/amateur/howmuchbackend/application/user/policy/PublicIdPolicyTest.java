package com.amateur.howmuchbackend.application.user.policy;

import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.user.status.UserStatusCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("공개 ID 정책")
class PublicIdPolicyTest {
    private final PublicIdPolicy publicIdPolicy = new PublicIdPolicy();

    @Test
    @DisplayName("사용자 입력의 앞뒤 공백을 제거하고 영문을 소문자로 정규화한다")
    void normalizesPublicId() {
        assertThat(publicIdPolicy.normalize("  Sua.Test_1  ")).isEqualTo("sua.test_1");
    }

    @Test
    @DisplayName("허용된 문자와 길이를 벗어난 공개 ID를 거부한다")
    void rejectsInvalidPublicId() {
        List<String> invalidValues = List.of("abc", "a".repeat(21), "수아01", "sua-01", "sua 01");

        for (String invalidValue : invalidValues) {
            assertThatThrownBy(() -> publicIdPolicy.normalize(invalidValue))
                    .isInstanceOfSatisfying(ApplicationException.class, exception ->
                            assertThat(exception.getStatusCode()).isEqualTo(UserStatusCode.INVALID_PUBLIC_ID));
        }
        assertThatThrownBy(() -> publicIdPolicy.normalize(null))
                .isInstanceOfSatisfying(ApplicationException.class, exception ->
                        assertThat(exception.getStatusCode()).isEqualTo(UserStatusCode.INVALID_PUBLIC_ID));
    }

    @Test
    @DisplayName("공개 ID 검색어의 공백과 대소문자를 정규화하고 한 글자 prefix를 허용한다")
    void normalizesPublicIdSearchPrefix() {
        assertThat(publicIdPolicy.normalizePrefix("  S_  ")).isEqualTo("s_");
        assertThat(publicIdPolicy.normalizePrefix("  " + "a".repeat(20) + "  "))
                .isEqualTo("a".repeat(20));
    }

    @Test
    @DisplayName("빈 값이나 허용되지 않은 문자를 포함한 공개 ID 검색어를 거부한다")
    void rejectsInvalidPublicIdSearchPrefix() {
        List<String> invalidValues = List.of("", "   ", "a".repeat(21), "수아", "sua-01", "sua 01");

        for (String invalidValue : invalidValues) {
            assertThatThrownBy(() -> publicIdPolicy.normalizePrefix(invalidValue))
                    .isInstanceOfSatisfying(ApplicationException.class, exception ->
                            assertThat(exception.getStatusCode())
                                    .isEqualTo(UserStatusCode.INVALID_PUBLIC_ID_SEARCH_PREFIX));
        }
        assertThatThrownBy(() -> publicIdPolicy.normalizePrefix(null))
                .isInstanceOfSatisfying(ApplicationException.class, exception ->
                        assertThat(exception.getStatusCode())
                                .isEqualTo(UserStatusCode.INVALID_PUBLIC_ID_SEARCH_PREFIX));
    }

    @Test
    @DisplayName("회원가입용 공개 ID를 user_ 접두사와 영문 소문자·숫자 12자리로 생성한다")
    void generatesPublicId() {
        for (int attempt = 0; attempt < 100; attempt++) {
            assertThat(publicIdPolicy.generate()).matches("^user_[a-z0-9]{12}$");
        }
    }
}
