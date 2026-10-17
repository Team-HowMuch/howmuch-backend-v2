package com.amateur.howmuchbackend.application.settlement.service;

import com.amateur.howmuchbackend.application.port.in.settlement.RecordRecurringSettlementFailureUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.command.RecordRecurringSettlementFailureCommand;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadRecurringSettlementPort;
import com.amateur.howmuchbackend.application.port.out.settlement.RecurringSettlementExecutionPort;
import com.amateur.howmuchbackend.application.settlement.policy.RecurringSettlementRetryPolicy;
import com.amateur.howmuchbackend.domain.settlement.recurring.RecurrenceSchedule;
import com.amateur.howmuchbackend.domain.settlement.recurring.RecurringSettlement;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Dispatcher가 cursor 스캔 주기마다 고정한 scheduled-through horizon으로 꼬리를 유한하게 만들고, 지속적으로
 * 새로 도래하는 행이 있어도 커서 뒤에 있던 오래된 retry를 결국 선택하는지 검증한다. horizon이 없으면(예전
 * 동작) 매 poll의 upper bound가 움직여 페이지가 항상 가득 차고 커서가 리셋되지 않아 오래된 retry가 굶는다.
 */
class RecurringSettlementDispatcherTest {
    private static final Long GROUP = 1L;
    private static final Long CREATOR = 2L;
    private static final Long MEMBER = 3L;
    private static final Long ACCOUNT = 4L;
    private static final LocalDateTime CREATED = LocalDateTime.of(2026, 10, 1, 0, 0);
    private static final LocalDateTime T0 = LocalDateTime.of(2026, 11, 10, 0, 0);

    private final MutableClock clock = new MutableClock(T0);
    private final FakeRecurring recurring = new FakeRecurring();
    private final FakeExecutions executions = new FakeExecutions(recurring);
    private final RecordingFailures failures = new RecordingFailures();
    private final RecurringSettlementDispatcher dispatcher = new RecurringSettlementDispatcher(
            recurring, executions, failures, new RecurringSettlementRetryPolicy(), clock);

    @Test
    void fixedHorizon_boundsEachScanCycle_andOldEligibleRetryIsNotStarved() {
        // Old retry A is behind the first cursor position; fresh B..E are within the horizon.
        UUID a = recurring.addRetry(T0.minusDays(10), T0.plusMinutes(2));
        recurring.addFresh(T0.minusDays(9)); // B - selected first, advancing the cursor past A
        recurring.addFresh(T0.minusDays(8)); // C
        recurring.addFresh(T0.minusDays(7)); // D
        recurring.addFresh(T0.minusDays(6)); // E

        // Cycle 1: moving now with a new post-horizon arrival every poll. The fixed horizon keeps the
        // eligible set finite, so the cycle drains B..E plus the first arrival and then wraps.
        List<LocalDateTime> horizons = new ArrayList<>();
        for (int poll = 0; poll < 6; poll++) {
            clock.at(T0.plusSeconds(10L * poll));
            recurring.addFresh(clock.now()); // newly due arrival each poll
            dispatcher.dispatch(1);
            horizons.add(recurring.calls.getLast().scheduledThrough());
        }
        List<UUID> launchedInCycleOne = List.copyOf(executions.launched);

        assertThat(horizons).containsOnly(T0); // horizon stayed fixed for the whole cycle
        assertThat(launchedInCycleOne).doesNotContain(a); // A was behind the cursor and not eligible yet
        // Post-horizon arrivals were excluded from cycle 1; only the finite <= horizon set ran.
        assertThat(launchedInCycleOne).hasSize(5);

        // Cycle 2: now is past A's retryAt, and a new cycle starts a fresh horizon at the current now.
        clock.at(T0.plusMinutes(3));
        recurring.addFresh(clock.now());
        int launched = dispatcher.dispatch(1);

        assertThat(launched).isEqualTo(1);
        assertThat(executions.lastLaunched()).isEqualTo(a);
        assertThat(recurring.calls.getLast().scheduledThrough()).isEqualTo(T0.plusMinutes(3));
        assertThat(recurring.calls.getLast().cursor()).isNull();
    }

    private static RecurringSettlement settlement(LocalDateTime nextRunAt, LocalDateTime retryAt, String failureCode,
            int failures) {
        LocalDate kstDate = nextRunAt.atOffset(ZoneOffset.UTC)
                .atZoneSameInstant(ZoneId.of("Asia/Seoul")).toLocalDate();
        var schedule = new RecurrenceSchedule(RecurrenceSchedule.Frequency.MONTHLY, null,
                kstDate.getDayOfMonth(), kstDate);
        return new RecurringSettlement(UUID.randomUUID(), GROUP, CREATOR, UUID.randomUUID(), "월세",
                "HOUSING_TELECOM", 20_000, ACCOUNT, schedule, nextRunAt, RecurringSettlement.Status.ACTIVE,
                List.of(new RecurringSettlement.Participant(CREATOR, 12_000, 0),
                        new RecurringSettlement.Participant(MEMBER, 8_000, 1)),
                List.of(new RecurringSettlement.Expense("월세", 20_000, 0)),
                failures, retryAt, failureCode, failureCode == null ? null : CREATED, null, null, CREATED, CREATED);
    }

    static final class MutableClock extends Clock {
        private final AtomicReference<Instant> instant;

        MutableClock(LocalDateTime start) {
            this.instant = new AtomicReference<>(start.toInstant(ZoneOffset.UTC));
        }

        void at(LocalDateTime time) {
            instant.set(time.toInstant(ZoneOffset.UTC));
        }

        LocalDateTime now() {
            return LocalDateTime.ofInstant(instant.get(), ZoneOffset.UTC);
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

    record Call(LocalDateTime now, LocalDateTime scheduledThrough, LoadRecurringSettlementPort.DueOccurrence cursor) {
    }

    /** SQL 가지와 같은 자격 규칙을 따르는 인메모리 read model. */
    static final class FakeRecurring implements LoadRecurringSettlementPort {
        private final List<Entry> entries = new ArrayList<>();
        private final List<Call> calls = new ArrayList<>();

        UUID addFresh(LocalDateTime nextRunAt) {
            return add(nextRunAt, null, null, 0);
        }

        UUID addRetry(LocalDateTime nextRunAt, LocalDateTime retryAt) {
            return add(nextRunAt, retryAt, RecurringSettlement.TRANSIENT_FAILURE, 1);
        }

        private UUID add(LocalDateTime nextRunAt, LocalDateTime retryAt, String failureCode, int failures) {
            var settlement = settlement(nextRunAt, retryAt, failureCode, failures);
            entries.add(new Entry(settlement));
            return settlement.id();
        }

        void advance(UUID id) {
            entries.stream().filter(entry -> entry.settlement.id().equals(id)).findFirst().ifPresent(entry ->
                    entry.settlement = settlement(entry.settlement.nextRunAt().plusYears(1), null, null, 0));
        }

        @Override
        public List<DueOccurrence> findDue(LocalDateTime now, LocalDateTime scheduledThrough, DueOccurrence cursor,
                int limit) {
            calls.add(new Call(now, scheduledThrough, cursor));
            return entries.stream()
                    .map(entry -> entry.settlement)
                    .filter(value -> eligible(value, now, scheduledThrough))
                    .filter(value -> afterCursor(value, cursor))
                    .sorted(Comparator.comparing(RecurringSettlement::nextRunAt).thenComparing(RecurringSettlement::id))
                    .limit(limit)
                    .map(value -> new DueOccurrence(value.id(), value.nextRunAt()))
                    .toList();
        }

        private boolean eligible(RecurringSettlement value, LocalDateTime now, LocalDateTime scheduledThrough) {
            if (value.nextRunAt().isAfter(scheduledThrough)) {
                return false;
            }
            boolean usableFailure = value.lastFailureCode() == null
                    || RecurringSettlement.TRANSIENT_FAILURE.equals(value.lastFailureCode());
            if (!usableFailure) {
                return false;
            }
            return value.retryAt() == null || !value.retryAt().isAfter(now);
        }

        private boolean afterCursor(RecurringSettlement value, DueOccurrence cursor) {
            if (cursor == null) {
                return true;
            }
            int timeOrder = value.nextRunAt().compareTo(cursor.scheduledFor());
            return timeOrder > 0 || (timeOrder == 0 && value.id().compareTo(cursor.id()) > 0);
        }

        @Override
        public Optional<RecurringSettlement> findById(UUID id) {
            return entries.stream().map(entry -> entry.settlement)
                    .filter(value -> value.id().equals(id)).findFirst();
        }

        @Override
        public Optional<RecurringSettlement> findByIdForUpdate(UUID id) {
            return findById(id);
        }

        @Override
        public Optional<RecurringSettlement> findBySourceSettlementId(UUID sourceId) {
            return Optional.empty();
        }

        private static final class Entry {
            private RecurringSettlement settlement;

            private Entry(RecurringSettlement settlement) {
                this.settlement = settlement;
            }
        }
    }

    static final class FakeExecutions implements RecurringSettlementExecutionPort {
        private final FakeRecurring recurring;
        private final List<UUID> launched = new ArrayList<>();

        FakeExecutions(FakeRecurring recurring) {
            this.recurring = recurring;
        }

        @Override
        public Execution inspect(UUID id, LocalDateTime scheduledFor) {
            return new Execution(State.NEW, 0, true, false, null);
        }

        @Override
        public LaunchResult launch(UUID id, LocalDateTime scheduledFor, Mode mode, int maximumAutomaticAttempts) {
            launched.add(id);
            recurring.advance(id);
            return LaunchResult.LAUNCHED;
        }

        UUID lastLaunched() {
            return launched.getLast();
        }
    }

    static final class RecordingFailures implements RecordRecurringSettlementFailureUseCase {
        @Override
        public void record(RecordRecurringSettlementFailureCommand command) {
            throw new AssertionError("unexpected failure record");
        }
    }
}
