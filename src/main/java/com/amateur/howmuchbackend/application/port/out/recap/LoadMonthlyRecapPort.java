package com.amateur.howmuchbackend.application.port.out.recap;

import com.amateur.howmuchbackend.application.recap.model.MonthlyRecap;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

public interface LoadMonthlyRecapPort {
    List<YearMonth> findMonths(Long ownerId);
    Optional<MonthlyRecap> find(Long ownerId, YearMonth month);
}
