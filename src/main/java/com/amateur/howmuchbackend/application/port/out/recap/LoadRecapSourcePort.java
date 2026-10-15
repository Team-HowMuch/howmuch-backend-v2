package com.amateur.howmuchbackend.application.port.out.recap;

import com.amateur.howmuchbackend.application.recap.model.RecapSourceFact;
import java.time.YearMonth;
import java.util.List;

public interface LoadRecapSourcePort {
    List<RecapSourceFact> findCompletedMonth(Long ownerId, YearMonth month);
}
