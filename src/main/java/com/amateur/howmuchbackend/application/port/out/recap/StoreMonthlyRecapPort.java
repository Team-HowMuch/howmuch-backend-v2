package com.amateur.howmuchbackend.application.port.out.recap;

import com.amateur.howmuchbackend.application.recap.model.MonthlyRecap;

public interface StoreMonthlyRecapPort {
    boolean insertIfAbsent(MonthlyRecap recap);
}
