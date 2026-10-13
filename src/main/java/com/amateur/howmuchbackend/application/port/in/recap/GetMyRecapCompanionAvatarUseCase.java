package com.amateur.howmuchbackend.application.port.in.recap;

import com.amateur.howmuchbackend.application.port.in.recap.result.*;
import java.time.YearMonth;

public interface GetMyRecapCompanionAvatarUseCase {
    RecapAvatarResult getCompanionAvatar(YearMonth month);
}
