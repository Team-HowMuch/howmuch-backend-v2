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
    @DisplayName("회원가입용 공개 ID를 user_ 접두사와 영문 소문자·숫자 12자리로 생성한다")
    void generatesPublicId() {
        for (int attempt = 0; attempt < 100; attempt++) {
            assertThat(publicIdPolicy.generate()).matches("^user_[a-z0-9]{12}$");
        }
    }
}
