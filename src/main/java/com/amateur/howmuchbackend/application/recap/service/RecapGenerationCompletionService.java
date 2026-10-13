package com.amateur.howmuchbackend.application.recap.service;

import com.amateur.howmuchbackend.application.port.out.recap.RecapGenerationJobStorePort;
import com.amateur.howmuchbackend.application.port.out.user.LoadUserPort;
import com.amateur.howmuchbackend.application.recap.model.MonthlyRecap;
import com.amateur.howmuchbackend.application.recap.model.RecapGenerationClaim;
import com.amateur.howmuchbackend.domain.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Service
@RequiredArgsConstructor
public class RecapGenerationCompletionService {
    private final LoadUserPort users;
    private final RecapGenerationJobStorePort jobs;
    private final RecapSnapshotService snapshots;

    @Transactional
    public boolean finish(RecapGenerationClaim claim, MonthlyRecap recap) {
        // Withdrawal takes the same owner -> job lock order.
        boolean active = users.findByIdForUpdate(claim.ownerId()).filter(User::active).isPresent();
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        if (!jobs.lockOwned(claim, now)) return false;
        if (!active || recap == null) {
            jobs.skip(claim, now, active);
            return true;
        }
        if (!claim.ownerId().equals(recap.ownerId()) || !claim.month().equals(recap.yearMonth())) {
            throw new IllegalArgumentException("Snapshot does not belong to the claimed owner/month");
        }
        boolean stored = snapshots.store(recap);
        jobs.complete(claim, now, stored);
        return true;
    }
}
