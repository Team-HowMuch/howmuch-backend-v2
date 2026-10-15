package com.amateur.howmuchbackend.application.port.in.recap;

import com.amateur.howmuchbackend.application.port.in.recap.result.*;
import java.time.YearMonth;

public interface GetMySharedRecapUseCase {
    SharedMonthlyRecapResult getShare(YearMonth month);
}
