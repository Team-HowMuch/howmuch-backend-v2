package com.amateur.howmuchbackend.application.recap.service;

import com.amateur.howmuchbackend.application.port.out.recap.StoreMonthlyRecapPort;
import com.amateur.howmuchbackend.application.port.out.recap.RecapPublicationStorePort;
import com.amateur.howmuchbackend.application.port.out.user.LoadUserPort;
import com.amateur.howmuchbackend.application.recap.model.MonthlyRecap;
import com.amateur.howmuchbackend.domain.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Service
@RequiredArgsConstructor
public class RecapSnapshotService {
    private final LoadUserPort users;
    private final StoreMonthlyRecapPort snapshots;
    private final RecapPublicationStorePort publication;
    private final Clock clock;

    @Transactional
    public boolean store(MonthlyRecap recap) {
        if (users.findByIdForUpdate(recap.ownerId()).filter(User::active).isEmpty()) {
            return false;
        }
        boolean stored = snapshots.insertIfAbsent(recap);
        if (stored) publication.enqueue(recap.id(), LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
        return stored;
    }
}
