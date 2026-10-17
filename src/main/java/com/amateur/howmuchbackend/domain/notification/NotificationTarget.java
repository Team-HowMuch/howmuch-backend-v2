package com.amateur.howmuchbackend.domain.notification;

import java.time.YearMonth;
import java.util.Objects;
import java.util.UUID;

public record NotificationTarget(NotificationTargetType type, String id) {
    public NotificationTarget {
        Objects.requireNonNull(type, "알림 이동 종류는 필수입니다.");
        Objects.requireNonNull(id, "알림 이동 식별자는 필수입니다.");
        try {
            id = switch (type) {
                case SETTLEMENT_PARTICIPATION, SETTLEMENT_DETAIL, GROUP_INVITATION,
                                GROUP_SETTLEMENT_NETTING -> {
                    UUID value = UUID.fromString(id);
                    if (!value.toString().equalsIgnoreCase(id)) throw new IllegalArgumentException();
                    yield value.toString();
                }
                case GROUP_DETAIL -> {
                    long value = Long.parseLong(id);
                    if (value < 1 || !Long.toString(value).equals(id)) throw new IllegalArgumentException();
                    yield Long.toString(value);
                }
                case MONTHLY_RECAP -> {
                    YearMonth value = YearMonth.parse(id);
                    if (!value.toString().equals(id) || value.getYear() < 1 || value.getYear() > 9999)
                        throw new IllegalArgumentException();
                    yield value.toString();
                }
            };
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("알림 이동 식별자가 유효하지 않습니다.", exception);
        }
    }
}
