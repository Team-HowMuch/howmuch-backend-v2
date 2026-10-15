package com.amateur.howmuchbackend.application.recap.policy;

import org.springframework.stereotype.Component;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;

@Component
public class RecapGenerationSchedulePolicy {
    public RegistrationWindow window(LocalDateTime nowUtc) {
        var current = nowUtc.atOffset(ZoneOffset.UTC).atZoneSameInstant(ZoneId.of("Asia/Seoul"));
        var month = YearMonth.from(current).minusMonths(1);
        var availableAt = month.plusMonths(1).atDay(1).atStartOfDay(ZoneId.of("Asia/Seoul"))
                .plusMinutes(10).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
        return new RegistrationWindow(month, availableAt);
    }
    public record RegistrationWindow(YearMonth month, LocalDateTime availableAt) {}
}
