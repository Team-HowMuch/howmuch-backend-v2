package com.amateur.howmuchbackend.application.recap.policy;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.time.LocalDateTime;
import static org.assertj.core.api.Assertions.*;

class RecapGenerationSchedulePolicyTest {
    private final RecapGenerationSchedulePolicy policy = new RecapGenerationSchedulePolicy();
    @ParameterizedTest
    @CsvSource({"2026-09-30T14:59:00,2026-08,2026-08-31T15:10:00",
            "2026-09-30T15:00:00,2026-09,2026-09-30T15:10:00",
            "2026-09-30T15:10:00,2026-09,2026-09-30T15:10:00",
            "2026-12-31T15:00:00,2026-12,2026-12-31T15:10:00"})
    void schedulesPreviousMonthAt0010Kst(String now, String month, String due) {
        var result = policy.window(LocalDateTime.parse(now));
        assertThat(result.month().toString()).isEqualTo(month);
        assertThat(result.availableAt()).isEqualTo(LocalDateTime.parse(due));
    }
}
