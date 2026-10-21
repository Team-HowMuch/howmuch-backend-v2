package com.amateur.howmuchbackend.application.settlement.netting;

import com.amateur.howmuchbackend.application.port.out.group.LoadGroupPort;
import com.amateur.howmuchbackend.application.port.out.settlement.netting.*;
import com.amateur.howmuchbackend.application.settlement.netting.policy.NettedTransferReminderPolicy;
import com.amateur.howmuchbackend.application.settlement.netting.service.NettedTransferReminderWriter;
import com.amateur.howmuchbackend.application.settlement.netting.service.NettingNotificationService;
import com.amateur.howmuchbackend.application.settlement.policy.SettlementReminderPolicy;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.group.Group;
import com.amateur.howmuchbackend.domain.settlement.netting.NettingStatusCode;
import com.amateur.howmuchbackend.domain.settlement.status.SettlementStatusCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.*;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NettedTransferReminderWriterTest {
    private static final long GROUP = 7L;
    private static final UUID NETTING = UUID.randomUUID(), TRANSFER = UUID.randomUUID();
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 21, 1, 0);
    @Mock LoadGroupPort groups;
    @Mock LoadGroupNettingPort nettings;
    @Mock SaveGroupNettingPort locking;
    @Mock NettedTransferReminderStorePort reminders;
    @Mock NettingNotificationService notifications;
    @Mock LoadGroupNettingDebtPort inputs;
    @Mock NettingTransferAvailabilityPort availability;
    private NettedTransferReminderWriter writer;

    @BeforeEach
    void setUp() {
        writer = new NettedTransferReminderWriter(groups, nettings, locking, reminders, notifications,
                inputs, availability, new NettedTransferReminderPolicy());
    }

    @ParameterizedTest
    @CsvSource({"COMPLETED,0,0,NETTING_REMINDER_NOT_ACTIVE",
            "SUPERSEDED,1,1000,NETTING_REMINDER_NOT_ACTIVE",
            "ACTIVE,1,500,NETTING_REMINDER_DEPOSIT_REVIEW_OPEN",
            "ACTIVE,0,1,NETTING_REMINDER_DEPOSIT_RECEIVED",
            "ACTIVE,0,1000,NETTING_REMINDER_DEPOSIT_RECEIVED"})
    void manualReminder_blocksWithSpecificCodeBeforeReservation(String status, int reviews, long confirmed,
            NettingStatusCode expected) {
        arrange(netting(status, reviews, confirmed));
        assertThatThrownBy(() -> writer.remind(GROUP, NETTING, TRANSFER, 1L, false, NOW))
                .isInstanceOfSatisfying(ApplicationException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(expected));
        verifyNoInteractions(reminders, notifications);
    }

    @ParameterizedTest
    @CsvSource({"COMPLETED,0,0", "ACTIVE,1,0", "ACTIVE,0,1"})
    void automaticReminder_blockedState_skipsWithoutReservation(String status, int reviews, long confirmed) {
        arrange(netting(status, reviews, confirmed));
        writer.remind(GROUP, NETTING, TRANSFER, null, true, NOW);
        verifyNoInteractions(reminders, notifications);
    }

    @Test
    void manualReminder_rechecksCurrentLockedNetting() {
        var initial = netting("ACTIVE", 0, 0);
        arrange(initial);
        when(nettings.find(GROUP, NETTING)).thenReturn(Optional.of(initial), Optional.of(netting("COMPLETED", 0, 0)));
        assertThatThrownBy(() -> writer.remind(GROUP, NETTING, TRANSFER, 1L, false, NOW))
                .isInstanceOfSatisfying(ApplicationException.class, error -> assertThat(error.getStatusCode())
                        .isEqualTo(NettingStatusCode.NETTING_REMINDER_NOT_ACTIVE));
        verify(locking).lockNetting(NETTING);
        verifyNoInteractions(reminders, notifications);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void allowedReminder_preservesManualAndAutomaticIntervals(boolean automatic) {
        var n = netting("ACTIVE", 0, 0);
        arrange(n);
        var interval = automatic ? SettlementReminderPolicy.AUTOMATIC_INTERVAL : SettlementReminderPolicy.COOLDOWN;
        when(reminders.reserve(TRANSFER, NOW, interval)).thenReturn(OptionalLong.of(1));
        writer.remind(GROUP, NETTING, TRANSFER, automatic ? null : 1L, automatic, NOW);
        verify(reminders).reserve(TRANSFER, NOW, interval);
        verify(notifications).reminder(n.transfers().getFirst(),
                (automatic ? "AUTO_NETTED_REMINDER:" : "NETTED_REMINDER:") + TRANSFER + ":1", NOW);
    }

    @Test
    void cooldown_stillReturnsExisting429Code() {
        arrange(netting("ACTIVE", 0, 0));
        assertThatThrownBy(() -> writer.remind(GROUP, NETTING, TRANSFER, 1L, false, NOW))
                .isInstanceOfSatisfying(ApplicationException.class, error -> assertThat(error.getStatusCode())
                        .isEqualTo(SettlementStatusCode.SETTLEMENT_REMINDER_COOLDOWN_ACTIVE));
        verifyNoInteractions(notifications);
    }

    @Test
    void nonReceiver_stillDeniedBeforeStateReason() {
        arrange(netting("ACTIVE", 1, 0));
        assertThatThrownBy(() -> writer.remind(GROUP, NETTING, TRANSFER, 2L, false, NOW))
                .isInstanceOfSatisfying(ApplicationException.class, error -> assertThat(error.getStatusCode())
                        .isEqualTo(SettlementStatusCode.SETTLEMENT_ACCESS_DENIED));
        verifyNoInteractions(reminders, notifications);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void processingRestriction_remainsBeforeStateReason(boolean automatic) {
        arrange(netting("COMPLETED", 1, 1000));
        when(availability.isGroupReplacementPending(GROUP)).thenReturn(true);
        if (automatic) {
            writer.remind(GROUP, NETTING, TRANSFER, null, true, NOW);
        } else {
            assertThatThrownBy(() -> writer.remind(GROUP, NETTING, TRANSFER, 1L, false, NOW))
                    .isInstanceOfSatisfying(ApplicationException.class, error -> assertThat(error.getStatusCode())
                            .isEqualTo(NettingStatusCode.NETTING_PROCESSING));
        }
        verifyNoInteractions(locking, reminders, notifications);
    }

    private void arrange(LoadGroupNettingPort.Netting n) {
        when(nettings.find(GROUP, NETTING)).thenReturn(Optional.of(n));
        when(groups.findByIdForUpdate(GROUP)).thenReturn(Optional.of(Group.create("모임", null, null, 1L, "ABCDEFGH", NOW)));
    }

    private LoadGroupNettingPort.Netting netting(String status, int reviews, long confirmed) {
        return new LoadGroupNettingPort.Netting(NETTING, GROUP, 1, status, 1L, NOW, null, null, List.of(),
                List.of(new LoadGroupNettingPort.Transfer(TRANSFER, NETTING, 2L, 1L, 1000, confirmed,
                        "ref", 1L, "bank", "masked", NOW.minusDays(1), null)), Set.of(1L, 2L), reviews);
    }
}
