package com.amateur.howmuchbackend.application.commoncode.service;

import com.amateur.howmuchbackend.application.auth.model.CurrentActor;
import com.amateur.howmuchbackend.application.port.in.commoncode.command.CommonCodeDetailCommand;
import com.amateur.howmuchbackend.application.port.in.commoncode.command.SearchCommonCodeGroupsCommand;
import com.amateur.howmuchbackend.application.port.in.commoncode.command.UpdateCommonCodeGroupCommand;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.application.port.out.commoncode.LoadCommonCodeGroupPort;
import com.amateur.howmuchbackend.application.port.out.commoncode.SaveCommonCodeGroupPort;
import com.amateur.howmuchbackend.application.port.out.user.LoadUserPort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.common.exception.status.CommonStatusCode;
import com.amateur.howmuchbackend.domain.commoncode.CommonCode;
import com.amateur.howmuchbackend.domain.commoncode.CommonCodeGroup;
import com.amateur.howmuchbackend.domain.user.User;
import com.amateur.howmuchbackend.domain.user.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("공통 코드 관리자 Service")
class CommonCodeAdminServiceTest {
    private static final Long ADMIN_ID = 1L;

    @Mock
    private CurrentActorPort currentActorPort;

    @Mock
    private LoadUserPort loadUserPort;

    @Mock
    private LoadCommonCodeGroupPort loadCommonCodeGroupPort;

    @Mock
    private SaveCommonCodeGroupPort saveCommonCodeGroupPort;

    private CommonCodeAdminService service;

    @BeforeEach
    void setUp() {
        service = new CommonCodeAdminService(
                currentActorPort,
                loadUserPort,
                loadCommonCodeGroupPort,
                saveCommonCodeGroupPort
        );
        when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(ADMIN_ID));
        when(loadUserPort.findById(ADMIN_ID)).thenReturn(Optional.of(user(UserRole.ADMIN)));
    }

    @Test
    @DisplayName("관리자만 그룹 검색을 수행한다")
    void commonCodeSearch_nonAdmin_rejected() {
        when(loadUserPort.findById(ADMIN_ID)).thenReturn(Optional.of(user(UserRole.USER)));

        assertThatThrownBy(() -> service.search(new SearchCommonCodeGroupsCommand(null)))
                .isInstanceOfSatisfying(ApplicationException.class, exception ->
                        assertThat(exception.getStatusCode()).isEqualTo(CommonStatusCode.FORBIDDEN));

        verify(loadCommonCodeGroupPort, never()).findAll(any());
    }

    @Test
    @DisplayName("그룹 revision과 attr1부터 attr3까지의 상세 변경을 저장한다")
    void updatesGroupAndDetails() {
        CommonCodeGroup current = new CommonCodeGroup(
                "BANK",
                "은행 코드",
                "기존 설명",
                "기존 URL",
                null,
                null,
                true,
                3L,
                List.of(new CommonCode("BANK", "0020", "우리은행", null, 1, null, null, null, true))
        );
        when(loadCommonCodeGroupPort.findByGroupCode("BANK")).thenReturn(Optional.of(current));
        when(saveCommonCodeGroupPort.update(any(CommonCodeGroup.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var result = service.update(
                "BANK",
                new UpdateCommonCodeGroupCommand(
                        3L,
                        "은행 코드",
                        "수정 설명",
                        "아이콘 URL",
                        "은행 홈페이지",
                        "추가 값",
                        true,
                        List.of(new CommonCodeDetailCommand(
                                "0020",
                                "우리은행",
                                "수정 상세",
                                2,
                                "https://example.com/icon.svg",
                                "https://example.com",
                                "bank-woori",
                                true
                        ))
                )
        );

        ArgumentCaptor<CommonCodeGroup> captor = ArgumentCaptor.forClass(CommonCodeGroup.class);
        verify(saveCommonCodeGroupPort).update(captor.capture());
        CommonCodeGroup saved = captor.getValue();
        assertThat(saved.attr1Name()).isEqualTo("아이콘 URL");
        assertThat(saved.attr2Name()).isEqualTo("은행 홈페이지");
        assertThat(saved.attr3Name()).isEqualTo("추가 값");
        assertThat(saved.details().getFirst().attr1()).isEqualTo("https://example.com/icon.svg");
        assertThat(saved.details().getFirst().attr2()).isEqualTo("https://example.com");
        assertThat(saved.details().getFirst().attr3()).isEqualTo("bank-woori");
        assertThat(result.details()).hasSize(1);
    }

    @Test
    @DisplayName("오래된 revision으로 저장하지 않는다")
    void rejectsStaleRevision() {
        CommonCodeGroup current = new CommonCodeGroup(
                "BANK", "은행 코드", null, null, null, null, true, 4L, List.of()
        );
        when(loadCommonCodeGroupPort.findByGroupCode("BANK")).thenReturn(Optional.of(current));

        assertThatThrownBy(() -> service.update(
                "BANK",
                new UpdateCommonCodeGroupCommand(3L, "은행 코드", null, null, null, null, true, List.of())
        )).isInstanceOfSatisfying(ApplicationException.class, exception ->
                assertThat(exception.getStatusCode()).isEqualTo(CommonStatusCode.CONCURRENT_UPDATE));

        verify(saveCommonCodeGroupPort, never()).update(any());
    }

    private User user(UserRole role) {
        LocalDateTime now = LocalDateTime.of(2026, 8, 23, 9, 0);
        return new User(
                ADMIN_ID,
                "identity-key",
                "관리자",
                "admin.id",
                null,
                null,
                "01012345678",
                "encoded-pin",
                null,
                null,
                true,
                role,
                true,
                null,
                now,
                null,
                now,
                now
        );
    }
}
