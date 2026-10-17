package com.amateur.howmuchbackend.support;

import com.amateur.howmuchbackend.domain.settlement.recurring.RecurrenceSchedule;
import com.amateur.howmuchbackend.domain.settlement.recurring.RecurringSettlement;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

public final class RecurringSettlementFixtures {
    public static final Long GROUP = 100L;
    public static final Long CREATOR = 101L;
    public static final Long MEMBER = 102L;
    public static final Long ACCOUNT = 103L;
    public static final String CATEGORY = "HOUSING_TELECOM";
    public static final LocalDateTime CREATED = LocalDateTime.of(2026, 10, 13, 0, 0);
    public static final LocalDateTime FIRST = LocalDateTime.of(2026, 10, 31, 0, 0);
    public static final Clock DUE_CLOCK = Clock.fixed(FIRST.plusMinutes(1).toInstant(ZoneOffset.UTC), ZoneOffset.UTC);

    private RecurringSettlementFixtures() {
    }

    public static RecurringSettlement template() {
        return RecurringSettlement.create(GROUP, CREATOR, UUID.randomUUID(), "월세", CATEGORY, 20_000, ACCOUNT,
                new RecurrenceSchedule(RecurrenceSchedule.Frequency.MONTHLY, null, 31, LocalDate.of(2026, 10, 31)),
                List.of(new RecurringSettlement.Participant(CREATOR, 12_000, 0),
                        new RecurringSettlement.Participant(MEMBER, 8_000, 1)),
                List.of(new RecurringSettlement.Expense("월세", 20_000, 0)), CREATED);
    }
}
