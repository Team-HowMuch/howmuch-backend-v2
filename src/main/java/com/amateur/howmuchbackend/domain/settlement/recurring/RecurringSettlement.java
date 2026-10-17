package com.amateur.howmuchbackend.domain.settlement.recurring;

import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.settlement.Settlement;
import com.amateur.howmuchbackend.domain.settlement.SettlementCategory;
import com.amateur.howmuchbackend.domain.settlement.SettlementExpense;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 그룹 고정 지출 반복 설정. 원본 DRAFT 초안에서 고정된 제목·총액·카테고리·수령 계좌·참여자·배분·지출 스냅샷을
 * 가지며, 예정 회차마다 새 Settlement를 발행한다. {@code categoryCode}는 설정의 기본 카테고리로, 회차 발행 시
 * {@code settlement_categories.source=RECURRING}으로 기록된다.
 */
public record RecurringSettlement(
        UUID id, Long groupId, Long creatorId, UUID sourceSettlementId,
        String title, String categoryCode, long totalAmount, Long receivingAccountId, RecurrenceSchedule schedule,
        LocalDateTime nextRunAt, Status status, List<Participant> participants, List<Expense> expenses,
        int failureCount, LocalDateTime retryAt, String lastFailureCode, LocalDateTime lastFailureAt,
        CancellationReason cancellationReason, LocalDateTime cancelledAt,
        LocalDateTime createdAt, LocalDateTime updatedAt
) {
    public static final int MAX_AUTOMATIC_ATTEMPTS = 3;
    public static final String TRANSIENT_FAILURE = "RECURRING_TRANSIENT_DATABASE_FAILURE";
    public static final String UNEXPECTED_FAILURE = "RECURRING_UNEXPECTED_EXECUTION_FAILURE";
    public static final String MANUAL_FAILURE = "RECURRING_MANUAL_EXECUTION_FAILURE";

    public enum Status { ACTIVE, CANCELLED }

    public enum CancellationReason {
        USER_CANCELLED, GROUP_UNAVAILABLE, CREATOR_UNAVAILABLE, PARTICIPANT_UNAVAILABLE, ACCOUNT_UNAVAILABLE
    }

    public record Participant(Long userId, long amount, int displayOrder) {
        public Participant {
            if (userId == null || userId <= 0 || amount <= 0 || displayOrder < 0) {
                invalidSource();
            }
        }
    }

    public record Expense(String description, long amount, int displayOrder) {
        public Expense {
            if (description == null || description.isBlank()
                    || description.length() > SettlementExpense.MAX_DESCRIPTION_LENGTH
                    || amount <= 0 || displayOrder < 0) {
                invalidSource();
            }
        }
    }

    public RecurringSettlement {
        Objects.requireNonNull(id);
        Objects.requireNonNull(sourceSettlementId);
        Objects.requireNonNull(schedule);
        Objects.requireNonNull(status);
        Objects.requireNonNull(createdAt);
        Objects.requireNonNull(updatedAt);
        participants = List.copyOf(participants);
        expenses = List.copyOf(expenses);
        if (groupId == null || groupId <= 0 || creatorId == null || creatorId <= 0
                || receivingAccountId == null || receivingAccountId <= 0
                || title == null || title.isBlank() || title.length() > Settlement.MAX_TITLE_LENGTH
                || !SettlementCategory.isStorableCode(categoryCode)
                || totalAmount <= 0 || participants.size() < 2 || expenses.isEmpty()
                || failureCount < 0 || failureCount > MAX_AUTOMATIC_ATTEMPTS) {
            invalidSource();
        }
        var users = new HashSet<Long>();
        var orders = new HashSet<Integer>();
        long participantTotal = 0;
        long expenseTotal = 0;
        try {
            for (Participant participant : participants) {
                if (!users.add(participant.userId()) || !orders.add(participant.displayOrder())) {
                    invalidSource();
                }
                participantTotal = Math.addExact(participantTotal, participant.amount());
            }
            orders.clear();
            for (Expense expense : expenses) {
                if (!orders.add(expense.displayOrder())) {
                    invalidSource();
                }
                expenseTotal = Math.addExact(expenseTotal, expense.amount());
            }
        } catch (ArithmeticException failure) {
            invalidSource();
        }
        if (!users.contains(creatorId) || participantTotal != totalAmount || expenseTotal != totalAmount) {
            invalidSource();
        }
        if ((status == Status.ACTIVE && (nextRunAt == null || cancellationReason != null || cancelledAt != null))
                || (status == Status.CANCELLED && (nextRunAt != null || cancellationReason == null || cancelledAt == null))) {
            invalidSource();
        }
        if ((lastFailureCode == null) != (lastFailureAt == null)
                || (lastFailureCode != null && !List.of(TRANSIENT_FAILURE, UNEXPECTED_FAILURE, MANUAL_FAILURE).contains(lastFailureCode))
                || (retryAt != null && (status != Status.ACTIVE || failureCount < 1
                    || failureCount >= MAX_AUTOMATIC_ATTEMPTS || !TRANSIENT_FAILURE.equals(lastFailureCode)))) {
            invalidSource();
        }
    }

    public static RecurringSettlement create(Long groupId, Long creatorId, UUID sourceId,
            String title, String categoryCode, long total, Long accountId, RecurrenceSchedule schedule,
            List<Participant> participants, List<Expense> expenses, LocalDateTime now) {
        schedule.requireFutureFirstRun(now);
        return new RecurringSettlement(UUID.randomUUID(), groupId, creatorId, sourceId,
                title, categoryCode, total, accountId, schedule, schedule.firstRunAt(), Status.ACTIVE,
                participants, expenses, 0, null, null, null, null, null, now, now);
    }

    public boolean automaticDue(LocalDateTime now) {
        return status == Status.ACTIVE && !nextRunAt.isAfter(now)
                && failureCount < MAX_AUTOMATIC_ATTEMPTS
                && (lastFailureCode == null || TRANSIENT_FAILURE.equals(lastFailureCode))
                && (retryAt == null || !retryAt.isAfter(now));
    }

    public boolean requiresManualReview() {
        return status == Status.ACTIVE && (failureCount >= MAX_AUTOMATIC_ATTEMPTS
                || (lastFailureCode != null && !TRANSIENT_FAILURE.equals(lastFailureCode)));
    }

    public RecurringSettlement cancel(CancellationReason reason, LocalDateTime now) {
        if (status == Status.CANCELLED) {
            return this;
        }
        return copy(null, Status.CANCELLED, failureCount, null, lastFailureCode,
                lastFailureAt, Objects.requireNonNull(reason), now, now);
    }

    public RecurringSettlement published(LocalDateTime scheduledFor, LocalDateTime now) {
        if (status != Status.ACTIVE || !Objects.equals(nextRunAt, scheduledFor)) {
            throw ApplicationException.of(RecurringSettlementStatusCode.RECURRING_SETTLEMENT_EXECUTION_NOT_DUE);
        }
        return copy(schedule.nextAfter(scheduledFor), status, 0, null, null, null, null, null, now);
    }

    public RecurringSettlement failed(boolean transientFailure, boolean automatic, LocalDateTime now) {
        if (status == Status.CANCELLED) {
            return this;
        }
        int failures = automatic ? Math.min(MAX_AUTOMATIC_ATTEMPTS, failureCount + 1) : failureCount;
        String code = automatic ? (transientFailure ? TRANSIENT_FAILURE : UNEXPECTED_FAILURE) : MANUAL_FAILURE;
        LocalDateTime retry = automatic && transientFailure && failures < MAX_AUTOMATIC_ATTEMPTS
                ? now.plusMinutes(failures == 1 ? 1 : 5) : null;
        return copy(nextRunAt, status, failures, retry, code, now, cancellationReason, cancelledAt, now);
    }

    private RecurringSettlement copy(LocalDateTime next, Status newStatus, int failures,
            LocalDateTime retry, String failureCode, LocalDateTime failureAt,
            CancellationReason reason, LocalDateTime cancelled, LocalDateTime now) {
        return new RecurringSettlement(id, groupId, creatorId, sourceSettlementId,
                title, categoryCode, totalAmount, receivingAccountId, schedule, next, newStatus, participants,
                expenses, failures, retry, failureCode, failureAt, reason, cancelled, createdAt, now);
    }

    private static void invalidSource() {
        throw ApplicationException.of(RecurringSettlementStatusCode.INVALID_RECURRING_SETTLEMENT_SOURCE);
    }
}
