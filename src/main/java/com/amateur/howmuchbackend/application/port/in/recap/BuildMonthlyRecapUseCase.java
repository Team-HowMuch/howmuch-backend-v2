package com.amateur.howmuchbackend.application.port.in.recap;

import com.amateur.howmuchbackend.application.recap.model.MonthlyRecap;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.Optional;

public interface BuildMonthlyRecapUseCase {
    Optional<MonthlyRecap> build(Long ownerId, YearMonth month, LocalDateTime now);
}
