package com.amateur.howmuchbackend.adapter.out.persistence.settlement;

import com.amateur.howmuchbackend.application.image.ImageCacheVersion;
import com.amateur.howmuchbackend.adapter.out.persistence.common.PostgresLockTimeoutConfigurer;
import com.amateur.howmuchbackend.adapter.out.persistence.settlement.mapper.SettlementMapper;
import com.amateur.howmuchbackend.adapter.out.persistence.settlement.repository.SettlementJpaRepository;
import com.amateur.howmuchbackend.application.port.in.settlement.command.SettlementListStatusFilter;
import com.amateur.howmuchbackend.application.port.in.settlement.command.SettlementListTab;
import com.amateur.howmuchbackend.application.port.out.settlement.DeleteSettlementPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementListPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadUserSettlementStatePort;
import com.amateur.howmuchbackend.application.port.out.settlement.SaveSettlementPort;
import com.amateur.howmuchbackend.application.port.out.settlement.SettlementListReadModel;
import com.amateur.howmuchbackend.application.port.out.settlement.SettlementReceivingAccountUsagePort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.settlement.Settlement;
import com.amateur.howmuchbackend.domain.settlement.SettlementAllocationType;
import com.amateur.howmuchbackend.domain.settlement.SettlementStatus;
import com.amateur.howmuchbackend.domain.settlement.status.SettlementStatusCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class SettlementPersistenceAdapter implements
        LoadSettlementPort,
        LoadUserSettlementStatePort,
        LoadSettlementListPort,
        SaveSettlementPort,
        DeleteSettlementPort,
        SettlementReceivingAccountUsagePort {
    private final SettlementJpaRepository settlementJpaRepository;
    private final PostgresLockTimeoutConfigurer lockTimeoutConfigurer;

    @Override
    public Settlement save(Settlement settlement) {
        return SettlementMapper.toDomain(
                settlementJpaRepository.save(SettlementMapper.toEntity(settlement))
        );
    }

    @Override
    public Optional<Settlement> findById(UUID settlementId) {
        return settlementJpaRepository.findByIdAndDeletedAtIsNull(settlementId)
                .map(SettlementMapper::toDomain);
    }

    @Override
    public boolean existsByUserIdAndStatusNotCompleted(Long userId) {
        return settlementJpaRepository.existsByUserIdAndStatusNotCompleted(userId);
    }

    @Override
    public Optional<Settlement> findByIdForUpdate(UUID settlementId) {
        lockTimeoutConfigurer.apply();
        return settlementJpaRepository.findByIdForUpdate(settlementId).map(SettlementMapper::toDomain);
    }

    @Override
    public List<SettlementListReadModel> findAll(
            Long userId,
            SettlementListTab tab,
            SettlementListStatusFilter status,
            Long groupId,
            YearMonth yearMonth
    ) {
        var projections = settlementJpaRepository.findAllForUser(
                userId, tab.name(), status.name(), groupId,
                yearMonth == null ? null : monthStartUtc(yearMonth),
                yearMonth == null ? null : monthStartUtc(yearMonth.plusMonths(1))
        );
        if (projections.isEmpty()) {
            return List.of();
        }
        Map<UUID, List<SettlementListReadModel.GroupSummary>> groupsBySettlementId =
                settlementJpaRepository.findGroupsForSettlements(
                        projections.stream()
                                .map(SettlementJpaRepository.SettlementListProjection::getSettlementId)
                                .toList(),
                        userId
                ).stream()
                        .collect(Collectors.groupingBy(
                                SettlementJpaRepository.SettlementListGroupProjection::getSettlementId,
                                Collectors.mapping(
                                        projection -> new SettlementListReadModel.GroupSummary(
                                                projection.getGroupId(),
                                                projection.getGroupName(),
                                                ImageCacheVersion.fromNullableFileName(
                                                        projection.getImageFileName()
                                                )
                                        ),
                                        Collectors.toList()
                                )
                        ));
        return projections.stream()
                .map(projection -> toReadModel(
                        projection,
                        tab,
                        groupsBySettlementId.getOrDefault(projection.getSettlementId(), List.of())
                ))
                .toList();
    }

    private Instant monthStartUtc(YearMonth yearMonth) {
        return yearMonth.atDay(1).atStartOfDay(ZoneId.of("Asia/Seoul"))
                .toInstant();
    }

    @Override
    public Settlement update(Settlement settlement) {
        var entity = settlementJpaRepository.findByIdAndDeletedAtIsNull(settlement.id())
                .orElseThrow(() -> ApplicationException.of(SettlementStatusCode.SETTLEMENT_NOT_FOUND));
        entity.updateFrom(settlement);
        return SettlementMapper.toDomain(settlementJpaRepository.save(entity));
    }

    @Override
    public void delete(UUID settlementId, LocalDateTime deletedAt) {
        if (settlementJpaRepository.markDeleted(settlementId, deletedAt) != 1) {
            throw ApplicationException.of(SettlementStatusCode.SETTLEMENT_NOT_FOUND);
        }
    }

    private SettlementListReadModel toReadModel(
            SettlementJpaRepository.SettlementListProjection projection,
            SettlementListTab tab,
            List<SettlementListReadModel.GroupSummary> groups
    ) {
        SettlementStatus status = SettlementStatus.valueOf(projection.getStatus());
        int completedParticipantCount = projection.getCompletedParticipantCount() == null
                ? 0
                : Math.toIntExact(projection.getCompletedParticipantCount());
        return new SettlementListReadModel(
                projection.getSettlementId(),
                status,
                projection.getRequestedAt(),
                projection.getEndedAt() == null
                        ? null
                        : LocalDateTime.ofInstant(projection.getEndedAt(), ZoneOffset.UTC),
                projection.getCompletedAt() == null
                        ? null
                        : LocalDateTime.ofInstant(projection.getCompletedAt(), ZoneOffset.UTC),
                tab == SettlementListTab.RECEIVE ? projection.getTotalAmount() : null,
                projection.getAllocationType() == null
                        ? null
                        : SettlementAllocationType.valueOf(projection.getAllocationType()),
                tab == SettlementListTab.RECEIVE
                        ? Math.toIntExact(projection.getParticipantCount())
                        : null,
                tab == SettlementListTab.RECEIVE
                        ? Math.toIntExact(projection.getPayableParticipantCount())
                        : null,
                tab == SettlementListTab.RECEIVE ? completedParticipantCount : null,
                tab == SettlementListTab.PAY ? projection.getAmountToPay() : null,
                tab == SettlementListTab.PAY ? projection.getRequesterName() : null,
                groups
        );
    }

    @Override
    public boolean existsInProgressByReceivingAccountId(Long accountId) {
        return settlementJpaRepository.existsByReceivingAccountIdAndStatusNotAndDeletedAtIsNull(
                accountId, SettlementStatus.COMPLETED);
    }

    @Override
    public void detachCompletedOrDeletedByReceivingAccountId(Long accountId) {
        settlementJpaRepository.detachCompletedOrDeletedByReceivingAccountId(accountId, SettlementStatus.COMPLETED);
    }
}
