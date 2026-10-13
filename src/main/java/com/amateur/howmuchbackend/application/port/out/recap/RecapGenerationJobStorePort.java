package com.amateur.howmuchbackend.application.port.out.recap;

import com.amateur.howmuchbackend.application.recap.model.RecapGenerationClaim;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.Optional;

public interface RecapGenerationJobStorePort {
    void registerMissingMonths(YearMonth lastMonth, LocalDateTime now);
    int recoverExpired(LocalDateTime now);
    Optional<RecapGenerationClaim> claimNext(LocalDateTime now);
    boolean renew(RecapGenerationClaim claim, LocalDateTime now);
    boolean reserveImage(RecapGenerationClaim claim, String fileName, LocalDateTime now);
    boolean lockOwned(RecapGenerationClaim claim, LocalDateTime now);
    void complete(RecapGenerationClaim claim, LocalDateTime now, boolean imageStored);
    void skip(RecapGenerationClaim claim, LocalDateTime now, boolean ownerActive);
    void retry(RecapGenerationClaim claim, LocalDateTime now, String failureCode);
}
