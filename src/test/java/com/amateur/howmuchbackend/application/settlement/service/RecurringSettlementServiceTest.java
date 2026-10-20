package com.amateur.howmuchbackend.application.settlement.service;

import com.amateur.howmuchbackend.application.auth.model.CurrentActor;
import com.amateur.howmuchbackend.application.port.in.settlement.command.CreateRecurringSettlementCommand;
import com.amateur.howmuchbackend.application.port.in.settlement.result.RecurringSettlementResult;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementCategoryResult;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadRecurringSettlementPort;
import com.amateur.howmuchbackend.application.settlement.policy.RecurringSettlementAccessPolicy;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.settlement.recurring.RecurrenceSchedule;
import com.amateur.howmuchbackend.domain.settlement.recurring.RecurringSettlement;
import com.amateur.howmuchbackend.domain.settlement.recurring.RecurringSettlementStatusCode;
import com.amateur.howmuchbackend.support.RecurringSettlementFixtures;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RecurringSettlementServiceTest {
    private static final long GROUP = RecurringSettlementFixtures.GROUP;
    private static final long CREATOR = RecurringSettlementFixtures.CREATOR;
    private static final long OTHER = 999L;

    private final CurrentActorPort currentActorPort = mock(CurrentActorPort.class);
    private final LoadRecurringSettlementPort recurring = mock(LoadRecurringSettlementPort.class);
    private final SettlementCategoryCatalog catalog = mock(SettlementCategoryCatalog.class);
    private final MutableClock clock = new MutableClock(RecurringSettlementFixtures.CREATED);

    private RecurringSettlementService service;
    private final RecurringSettlementPresentationAssembler presentation = mock(RecurringSettlementPresentationAssembler.class);

    @BeforeEach
    void setUp() {
        when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(CREATOR));
        when(catalog.byCode()).thenReturn(Map.of(
                "HOUSING_TELECOM", new SettlementCategoryResult("HOUSING_TELECOM", "주거", "월세", "housing-telecom"),
                "SHOPPING", new SettlementCategoryResult("SHOPPING", "쇼핑", null, "shopping"),
                "UNCATEGORIZED", new SettlementCategoryResult("UNCATEGORIZED", "미분류", null, "uncategorized")));
        org.mockito.Mockito.when(presentation.assemble(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> RecurringSettlementResult.from(invocation.getArgument(0)));
        service = new RecurringSettlementService(
                currentActorPort, recurring, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, new RecurringSettlementAccessPolicy(), null,
                new RecurrenceScheduleResolver(clock), null, catalog, null, presentation);
    }

    @Test
    void unknownCategory_isRejectedButUncategorizedIsAllowed() {
        assertThatThrownBy(() -> service.create(GROUP, command("NOPE", null)))
                .isInstanceOfSatisfying(ApplicationException.class, exception ->
                        assertThat(exception.getStatusCode())
                                .isEqualTo(RecurringSettlementStatusCode.INVALID_RECURRING_SETTLEMENT_CATEGORY));
        var existing = template("UNCATEGORIZED");
        when(recurring.findBySourceSettlementId(existing.sourceSettlementId())).thenReturn(Optional.of(existing));
        RecurringSettlementResult result = service.create(GROUP,
                new CreateRecurringSettlementCommand(existing.sourceSettlementId(),
                        RecurrenceSchedule.Frequency.MONTHLY, null, 31, LocalDate.of(2026, 10, 31), "UNCATEGORIZED"));
        assertThat(result.categoryCode()).isEqualTo("UNCATEGORIZED");
    }

    @Test
    void identicalExplicitReplay_afterFirstRunElapsed_returnsTheOriginalWithoutFutureValidation() {
        var existing = template("HOUSING_TELECOM");
        when(recurring.findBySourceSettlementId(existing.sourceSettlementId())).thenReturn(Optional.of(existing));
        clock.at(LocalDateTime.of(2026, 12, 1, 0, 0));

        RecurringSettlementResult result = service.create(GROUP,
                new CreateRecurringSettlementCommand(existing.sourceSettlementId(),
                        RecurrenceSchedule.Frequency.MONTHLY, null, 31, LocalDate.of(2026, 10, 31),
                        "HOUSING_TELECOM"));

        assertThat(result.id()).isEqualTo(existing.id());
    }

    @Test
    void identicalOmittedReplay_afterFirstRunElapsed_returnsTheOriginal() {
        var existing = template("HOUSING_TELECOM");
        when(recurring.findBySourceSettlementId(existing.sourceSettlementId())).thenReturn(Optional.of(existing));
        clock.at(LocalDateTime.of(2026, 12, 1, 0, 0));

        RecurringSettlementResult result = service.create(GROUP,
                new CreateRecurringSettlementCommand(existing.sourceSettlementId(),
                        RecurrenceSchedule.Frequency.MONTHLY, null, 31, null, "HOUSING_TELECOM"));

        assertThat(result.id()).isEqualTo(existing.id());
    }

    @Test
    void replayWithDifferentCategoryCadenceOrDate_conflicts() {
        var existing = template("HOUSING_TELECOM");
        when(recurring.findBySourceSettlementId(existing.sourceSettlementId())).thenReturn(Optional.of(existing));

        assertThat(conflict(new CreateRecurringSettlementCommand(existing.sourceSettlementId(),
                RecurrenceSchedule.Frequency.MONTHLY, null, 31, LocalDate.of(2026, 10, 31), "SHOPPING")))
                .isEqualTo(RecurringSettlementStatusCode.RECURRING_SETTLEMENT_SOURCE_ALREADY_USED);
        assertThat(conflict(new CreateRecurringSettlementCommand(existing.sourceSettlementId(),
                RecurrenceSchedule.Frequency.MONTHLY, null, 15, null, "HOUSING_TELECOM")))
                .isEqualTo(RecurringSettlementStatusCode.RECURRING_SETTLEMENT_SOURCE_ALREADY_USED);
        assertThat(conflict(new CreateRecurringSettlementCommand(existing.sourceSettlementId(),
                RecurrenceSchedule.Frequency.MONTHLY, null, 31, LocalDate.of(2026, 11, 30), "HOUSING_TELECOM")))
                .isEqualTo(RecurringSettlementStatusCode.RECURRING_SETTLEMENT_SOURCE_ALREADY_USED);
        assertThat(conflict(new CreateRecurringSettlementCommand(existing.sourceSettlementId(),
                RecurrenceSchedule.Frequency.WEEKLY, DayOfWeek.MONDAY, null, null, "HOUSING_TELECOM")))
                .isEqualTo(RecurringSettlementStatusCode.RECURRING_SETTLEMENT_SOURCE_ALREADY_USED);
    }

    @Test
    void nonCreatorReplay_isDenied() {
        var existing = template("HOUSING_TELECOM");
        when(recurring.findBySourceSettlementId(existing.sourceSettlementId())).thenReturn(Optional.of(existing));
        when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(OTHER));

        assertThatThrownBy(() -> service.create(GROUP,
                new CreateRecurringSettlementCommand(existing.sourceSettlementId(),
                        RecurrenceSchedule.Frequency.MONTHLY, null, 31, null, "HOUSING_TELECOM")))
                .isInstanceOfSatisfying(ApplicationException.class, exception ->
                        assertThat(exception.getStatusCode())
                                .isEqualTo(RecurringSettlementStatusCode.RECURRING_SETTLEMENT_ACCESS_DENIED));
    }

    private RecurringSettlementStatusCode conflict(CreateRecurringSettlementCommand command) {
        try {
            service.create(GROUP, command);
            throw new AssertionError("expected conflict");
        } catch (ApplicationException expected) {
            return (RecurringSettlementStatusCode) expected.getStatusCode();
        }
    }

    private RecurringSettlement template(String categoryCode) {
        return RecurringSettlement.create(GROUP, CREATOR, UUID.randomUUID(), "월세", categoryCode, 20_000,
                RecurringSettlementFixtures.ACCOUNT,
                new RecurrenceSchedule(RecurrenceSchedule.Frequency.MONTHLY, null, 31, LocalDate.of(2026, 10, 31)),
                java.util.List.of(new RecurringSettlement.Participant(CREATOR, 12_000, 0),
                        new RecurringSettlement.Participant(RecurringSettlementFixtures.MEMBER, 8_000, 1)),
                java.util.List.of(new RecurringSettlement.Expense("월세", 20_000, 0)),
                RecurringSettlementFixtures.CREATED);
    }

    private CreateRecurringSettlementCommand command(String categoryCode, LocalDate firstRunDate) {
        return new CreateRecurringSettlementCommand(UUID.randomUUID(), RecurrenceSchedule.Frequency.MONTHLY,
                null, 31, firstRunDate, categoryCode);
    }

    static final class MutableClock extends Clock {
        private final AtomicReference<Instant> instant;

        MutableClock(LocalDateTime start) {
            this.instant = new AtomicReference<>(start.toInstant(ZoneOffset.UTC));
        }

        void at(LocalDateTime time) {
            instant.set(time.toInstant(ZoneOffset.UTC));
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(instant(), zone);
        }

        @Override
        public Instant instant() {
            return instant.get();
        }
    }
}
