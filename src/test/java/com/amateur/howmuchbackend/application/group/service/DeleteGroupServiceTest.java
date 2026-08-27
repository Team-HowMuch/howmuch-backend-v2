package com.amateur.howmuchbackend.application.group.service;

import com.amateur.howmuchbackend.application.auth.model.CurrentActor;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.application.port.out.group.SaveGroupPort;
import com.amateur.howmuchbackend.domain.group.Group;
import com.amateur.howmuchbackend.domain.group.GroupStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("그룹 삭제 서비스")
class DeleteGroupServiceTest {
    @Mock private CurrentActorPort currentActorPort;
    @Mock private SaveGroupPort saveGroupPort;
    @Mock private GroupAccessService groupAccessService;
    @Mock private GroupImageTransactionSupport groupImageTransactionSupport;
    @InjectMocks private DeleteGroupService service;

    @Test
    @DisplayName("OWNER가 그룹을 소프트 삭제하고 commit 후 이미지 정리를 예약한다")
    void softDeletesGroup() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 25, 6, 0);
        Group group = new Group(10L, "여행", null, "image.jpg", 1L, "AB12CD34",
                GroupStatus.ACTIVE, 1L, now, now, null);
        when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(1L));
        when(groupAccessService.loadActiveForUpdate(10L)).thenReturn(group);

        service.delete(10L);

        verify(groupAccessService).requireOwner(10L, 1L);
        ArgumentCaptor<Group> groupCaptor = ArgumentCaptor.forClass(Group.class);
        verify(saveGroupPort).update(groupCaptor.capture());
        assertThat(groupCaptor.getValue().status()).isEqualTo(GroupStatus.DELETED);
        assertThat(groupCaptor.getValue().imageFileName()).isNull();
        verify(groupImageTransactionSupport).replaceAfterCompletion("image.jpg", null, null);
    }
}
