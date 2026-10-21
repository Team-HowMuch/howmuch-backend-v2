package com.amateur.howmuchbackend.application.settlement.netting;

import com.amateur.howmuchbackend.application.auth.model.CurrentActor;
import com.amateur.howmuchbackend.application.group.service.GroupAccessService;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.application.port.out.group.LoadGroupPort;
import com.amateur.howmuchbackend.application.port.out.settlement.SettlementTransferLinkPort;
import com.amateur.howmuchbackend.application.port.out.settlement.netting.*;
import com.amateur.howmuchbackend.application.port.out.user.LoadUserPort;
import com.amateur.howmuchbackend.application.settlement.netting.service.GroupNettingAccessService;
import com.amateur.howmuchbackend.application.settlement.netting.service.GroupNettingQueryService;
import com.amateur.howmuchbackend.application.settlement.service.MySettlementCategoryReader;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.group.Group;
import com.amateur.howmuchbackend.domain.settlement.netting.NettingStatusCode;
import com.amateur.howmuchbackend.domain.user.User;
import com.amateur.howmuchbackend.domain.user.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.*;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GroupNettingQueryServiceTest {
    private static final long GROUP = 7L;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 21, 1, 0);
    @Mock CurrentActorPort actor;
    @Mock GroupNettingAccessService access;
    @Mock GroupAccessService groups;
    @Mock LoadGroupPort groupRecords;
    @Mock LoadGroupNettingPort nettings;
    @Mock LoadUserPort users;
    @Mock SettlementTransferLinkPort links;
    @Mock NettingTransferAvailabilityPort availability;
    @Mock MySettlementCategoryReader categories;
    @Mock NettedTransferReminderStorePort reminders;
    private GroupNettingQueryService service;

    @BeforeEach
    void setUp() {
        service = new GroupNettingQueryService(actor, access, groups, groupRecords, nettings, users,
                links, availability, categories, reminders,
                Clock.fixed(NOW.toInstant(ZoneOffset.UTC), ZoneId.of("Asia/Seoul")));
        when(actor.getCurrentActor()).thenReturn(new CurrentActor(1L));
    }

    @Test
    void list_batchesAllNettingTransfersOnce_andUsesUtcCooldownBoundary() {
        var a = netting(UUID.randomUUID(), UUID.randomUUID());
        var b = netting(UUID.randomUUID(), UUID.randomUUID());
        arrangeList(List.of(a, b));
        arrangeUsers();
        var ids = List.of(a.transfers().get(0).id(), a.transfers().get(1).id(),
                b.transfers().get(0).id(), b.transfers().get(1).id());
        when(reminders.findLastRemindedAt(ids)).thenReturn(Map.of(
                ids.get(0), NOW.minusMinutes(10),
                ids.get(1), NOW.minusMinutes(30),
                ids.get(2), NOW.minusMinutes(31)));

        var result = service.list(GROUP);

        assertThat(result.get(0).transfers().get(0).reminderNextAvailableAt()).isEqualTo(NOW.plusMinutes(20));
        assertThat(result.get(0).transfers().get(1).reminderNextAvailableAt()).isNull();
        assertThat(result.get(1).transfers()).allSatisfy(t -> assertThat(t.reminderNextAvailableAt()).isNull());
        verify(reminders).findLastRemindedAt(ids);
        verifyNoMoreInteractions(reminders);
    }

    @Test
    void get_readsCooldownOnceAfterAccessCheck() {
        var n = netting(UUID.randomUUID());
        when(access.requireAccess(GROUP, n.id(), 1L)).thenReturn(n);
        arrangeUsers();
        when(reminders.findLastRemindedAt(List.of(n.transfers().getFirst().id())))
                .thenReturn(Map.of(n.transfers().getFirst().id(), NOW.minusMinutes(5)));

        assertThat(service.get(GROUP, n.id()).transfers().getFirst().reminderNextAvailableAt())
                .isEqualTo(NOW.plusMinutes(25));
        var order = inOrder(access, reminders);
        order.verify(access).requireAccess(GROUP, n.id(), 1L);
        order.verify(reminders).findLastRemindedAt(List.of(n.transfers().getFirst().id()));
    }

    @Test
    void get_missingReminder_returnsNull() {
        var n = netting(UUID.randomUUID());
        when(access.requireAccess(GROUP, n.id(), 1L)).thenReturn(n);
        arrangeUsers();
        assertThat(service.get(GROUP, n.id()).transfers().getFirst().reminderNextAvailableAt()).isNull();
    }

    @Test
    void list_emptyHistory_skipsReminderRead() {
        arrangeList(List.of());
        assertThat(service.list(GROUP)).isEmpty();
        verifyNoInteractions(reminders, users);
    }

    @Test
    void get_noTransfers_skipsReminderRead() {
        var n = netting();
        when(access.requireAccess(GROUP, n.id(), 1L)).thenReturn(n);
        arrangeUsers();
        assertThat(service.get(GROUP, n.id()).transfers()).isEmpty();
        verifyNoInteractions(reminders);
    }

    @Test
    void get_accessDenied_doesNotReadCooldown() {
        var id = UUID.randomUUID();
        when(access.requireAccess(GROUP, id, 1L))
                .thenThrow(ApplicationException.of(NettingStatusCode.NETTING_ACCESS_DENIED));
        assertThatThrownBy(() -> service.get(GROUP, id)).isInstanceOf(ApplicationException.class);
        verifyNoInteractions(reminders, users);
    }

    private void arrangeList(List<LoadGroupNettingPort.Netting> values) {
        when(groupRecords.findById(GROUP)).thenReturn(Optional.of(Group.create("모임", null, null, 1L, "ABCDEFGH", NOW)));
        when(nettings.findAll(GROUP)).thenReturn(values.stream().map(LoadGroupNettingPort.Netting::id).toList());
        values.forEach(n -> when(nettings.find(GROUP, n.id())).thenReturn(Optional.of(n)));
    }

    private void arrangeUsers() {
        when(users.findAllByIds(any())).thenReturn(List.of(user(1L), user(2L)));
    }

    private User user(long id) {
        return new User(id, "identity-" + id, "회원" + id, "user" + id, null, null, "test-phone", "unused",
                null, null, true, UserRole.USER, true, null, null, null, NOW, NOW);
    }

    private LoadGroupNettingPort.Netting netting(UUID... transfers) {
        var id = UUID.randomUUID();
        return new LoadGroupNettingPort.Netting(id, GROUP, 1, "ACTIVE", 1L, NOW, null, null, List.of(),
                Arrays.stream(transfers).map(t -> new LoadGroupNettingPort.Transfer(t, id, 2L, 1L,
                        1000L, 0L, "reference", 1L, "bank", "masked", NOW, null)).toList(), Set.of(1L, 2L), 0);
    }
}
