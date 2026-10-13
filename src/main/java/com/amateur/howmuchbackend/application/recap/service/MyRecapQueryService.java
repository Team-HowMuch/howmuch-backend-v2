package com.amateur.howmuchbackend.application.recap.service;

import com.amateur.howmuchbackend.application.port.in.recap.*;
import com.amateur.howmuchbackend.application.port.in.recap.result.*;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.application.port.out.recap.LoadMonthlyRecapPort;
import com.amateur.howmuchbackend.application.port.out.recap.RecapImageStoragePort;
import com.amateur.howmuchbackend.application.port.out.user.LoadUserPort;
import com.amateur.howmuchbackend.application.recap.model.MonthlyRecap;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.common.exception.status.CommonStatusCode;
import com.amateur.howmuchbackend.domain.recap.status.RecapStatusCode;
import com.amateur.howmuchbackend.domain.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.YearMonth;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MyRecapQueryService implements GetMyRecapMonthsUseCase, GetMyMonthlyRecapUseCase,
        GetMySharedRecapUseCase, GetMyRecapCompanionAvatarUseCase {
    private final CurrentActorPort actor;
    private final LoadUserPort users;
    private final LoadMonthlyRecapPort snapshots;
    private final RecapImageStoragePort images;

    @Override public RecapMonthsResult getMonths() { return new RecapMonthsResult(snapshots.findMonths(owner())); }
    @Override public MonthlyRecapResult getRecap(YearMonth month) { return MonthlyRecapResult.from(find(month)); }
    @Override public SharedMonthlyRecapResult getShare(YearMonth month) { return SharedMonthlyRecapResult.from(find(month)); }
    @Override public RecapAvatarResult getCompanionAvatar(YearMonth month) {
        var companion = find(month).companion();
        if (companion == null || companion.avatarFileName() == null) {
            throw ApplicationException.of(RecapStatusCode.RECAP_IMAGE_NOT_FOUND);
        }
        var image = images.load(companion.avatarFileName())
                .orElseThrow(() -> ApplicationException.of(RecapStatusCode.RECAP_IMAGE_NOT_FOUND));
        return new RecapAvatarResult(image.contentType(), image.content());
    }

    private MonthlyRecap find(YearMonth month) {
        return snapshots.find(owner(), month).orElseThrow(() -> ApplicationException.of(RecapStatusCode.RECAP_NOT_FOUND));
    }
    private Long owner() {
        Long id = actor.getCurrentActor().userId();
        if (users.findById(id).filter(User::active).isEmpty()) throw ApplicationException.of(CommonStatusCode.UNAUTHORIZED);
        return id;
    }
}
