package com.amateur.howmuchbackend.application.group.policy;

import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.group.status.GroupStatusCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("그룹 초대 코드 정책")
class GroupInviteCodePolicyTest {
    private final GroupInviteCodePolicy policy = new GroupInviteCodePolicy();

    @Test
    @DisplayName("초대 코드의 바깥 공백을 제거하고 대문자로 정규화한다")
    void groupInviteCode_outerWhitespace_removedAndUppercase() {
        assertThat(policy.normalize("  ab12cd34  ")).isEqualTo("AB12CD34");
    }

    @Test
    @DisplayName("8자리 영숫자가 아닌 초대 코드를 거절한다")
    void groupInviteCode_invalidFormat_rejected() {
        assertThatThrownBy(() -> policy.normalize("AB12-CD3"))
                .isInstanceOfSatisfying(ApplicationException.class, exception ->
                        assertThat(exception.getStatusCode())
                                .isEqualTo(GroupStatusCode.INVALID_GROUP_INVITE_CODE));
    }

    @Test
    @DisplayName("null과 공백 초대 코드를 같은 기능 오류로 거절한다")
    void rejectsMissingInviteCode() {
        assertInvalidInviteCode(null);
        assertInvalidInviteCode("   ");
    }

    @Test
    @DisplayName("생성한 초대 코드는 8자리 대문자 영숫자다")
    void generatesInviteCode() {
        assertThat(policy.generate()).matches("^[A-Z0-9]{8}$");
    }

    private void assertInvalidInviteCode(String inviteCode) {
        assertThatThrownBy(() -> policy.normalize(inviteCode))
                .isInstanceOfSatisfying(ApplicationException.class, exception ->
                        assertThat(exception.getStatusCode())
                                .isEqualTo(GroupStatusCode.INVALID_GROUP_INVITE_CODE));
    }
}
