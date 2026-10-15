package com.amateur.howmuchbackend.application.recap.policy;

import com.amateur.howmuchbackend.application.recap.model.RecapComparison;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

import static com.amateur.howmuchbackend.application.recap.model.RecapComparison.Status.*;

@Component
public class RecapComparisonPolicy {
    public RecapComparison compare(long current, Long previous) {
        if (current < 0 || previous != null && previous < 0) {
            throw new IllegalArgumentException("Recap amounts must be nonnegative");
        }
        if (previous == null) {
            return new RecapComparison(NO_PREVIOUS, null, null, null);
        }
        long difference = Math.subtractExact(current, previous);
        if (difference == 0) {
            return new RecapComparison(UNCHANGED, previous, 0L, new BigDecimal("0.0"));
        }
        if (previous == 0) {
            return new RecapComparison(PREVIOUS_ZERO, 0L, current, null);
        }
        BigDecimal rate = BigDecimal.valueOf(difference).multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(previous), 1, RoundingMode.HALF_UP);
        return new RecapComparison(difference > 0 ? INCREASED : DECREASED, previous, difference, rate);
    }
}
