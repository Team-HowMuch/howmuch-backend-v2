package com.amateur.howmuchbackend.adapter.out.persistence.commoncode;

import com.amateur.howmuchbackend.adapter.out.persistence.commoncode.mapper.CommonCodeMapper;
import com.amateur.howmuchbackend.adapter.out.persistence.commoncode.mapper.CommonCodeGroupMapper;
import com.amateur.howmuchbackend.adapter.out.persistence.commoncode.entity.CommonCodeGroupJpaEntity;
import com.amateur.howmuchbackend.adapter.out.persistence.commoncode.entity.CommonCodeJpaEntity;
import com.amateur.howmuchbackend.adapter.out.persistence.commoncode.repository.CommonCodeGroupJpaRepository;
import com.amateur.howmuchbackend.adapter.out.persistence.commoncode.repository.CommonCodeJpaRepository;
import com.amateur.howmuchbackend.application.port.out.commoncode.LoadCommonCodeGroupPort;
import com.amateur.howmuchbackend.application.port.out.commoncode.LoadCommonCodePort;
import com.amateur.howmuchbackend.application.port.out.commoncode.SaveCommonCodeGroupPort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.common.exception.status.CommonStatusCode;
import com.amateur.howmuchbackend.domain.commoncode.CommonCode;
import com.amateur.howmuchbackend.domain.commoncode.CommonCodeGroup;
import com.amateur.howmuchbackend.domain.commoncode.CommonCodeGroupSummary;
import com.amateur.howmuchbackend.domain.commoncode.status.CommonCodeStatusCode;
import lombok.RequiredArgsConstructor;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.OptimisticLockException;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class CommonCodePersistenceAdapter
        implements LoadCommonCodePort, LoadCommonCodeGroupPort, SaveCommonCodeGroupPort {
    private static final List<String> CACHE_NAMES = List.of(
            "commonCodeByGroupAndCode",
            "commonCodeByGroup",
            "commonCodeExistsByGroupAndCode"
    );

    private final CommonCodeGroupJpaRepository commonCodeGroupRepository;
    private final CommonCodeJpaRepository commonCodeRepository;
    private final CacheManager cacheManager;
    private final EntityManager entityManager;

    @Override
    @Cacheable(cacheNames = "commonCodeByGroupAndCode", sync = true)
    public Optional<CommonCode> findActiveByGroupCodeAndCode(String groupCode, String code) {
        return commonCodeRepository.findActiveByGroupCodeAndCode(groupCode, code)
                .map(CommonCodeMapper::toDomain);
    }

    @Override
    @Cacheable(cacheNames = "commonCodeByGroup", sync = true)
    public List<CommonCode> findActiveByGroupCode(String groupCode) {
        return commonCodeRepository.findActiveByGroupCode(groupCode).stream()
                .map(CommonCodeMapper::toDomain)
                .toList();
    }

    @Override
    @Cacheable(cacheNames = "commonCodeExistsByGroupAndCode", sync = true)
    public boolean existsActiveByGroupCodeAndCode(String groupCode, String code) {
        return commonCodeRepository.existsActiveByGroupCodeAndCode(groupCode, code);
    }

    @Override
    public List<CommonCodeGroupSummary> findAll(String keyword) {
        List<CommonCodeGroupJpaEntity> groups = keyword == null || keyword.isBlank()
                ? commonCodeGroupRepository.findAllByOrderByGroupCodeAsc()
                : commonCodeGroupRepository.findAllByKeyword(keyword.trim());
        if (groups.isEmpty()) {
            return List.of();
        }

        List<String> groupCodes = groups.stream()
                .map(CommonCodeGroupJpaEntity::getGroupCode)
                .toList();
        Map<String, Long> detailCounts = commonCodeRepository.countByGroupCodes(groupCodes).stream()
                .collect(Collectors.toMap(
                        CommonCodeJpaRepository.GroupDetailCountProjection::getGroupCode,
                        CommonCodeJpaRepository.GroupDetailCountProjection::getDetailCount
                ));
        return groups.stream()
                .map(group -> CommonCodeGroupMapper.toSummary(
                        group,
                        detailCounts.getOrDefault(group.getGroupCode(), 0L)
                ))
                .toList();
    }

    @Override
    public Optional<CommonCodeGroup> findByGroupCode(String groupCode) {
        return commonCodeGroupRepository.findById(groupCode)
                .map(group -> CommonCodeGroupMapper.toDomain(group, findDetails(groupCode)));
    }

    @Override
    public CommonCodeGroup create(CommonCodeGroup group) {
        return saveGroupAndDetails(group, new CommonCodeGroupJpaEntity(group));
    }

    @Override
    public CommonCodeGroup update(CommonCodeGroup group) {
        CommonCodeGroupJpaEntity groupEntity = commonCodeGroupRepository.findById(group.groupCode())
                .orElseThrow(() -> ApplicationException.of(CommonCodeStatusCode.COMMON_CODE_GROUP_NOT_FOUND));
        if (groupEntity.getRevision() != group.revision()) {
            throw ApplicationException.of(CommonStatusCode.CONCURRENT_UPDATE);
        }
        try {
            // Details are separate entities: claim the edit revision before any detail writes.
            // Increment immediately so the returned revision also matches the committed state.
            entityManager.lock(groupEntity, LockModeType.PESSIMISTIC_FORCE_INCREMENT);
        } catch (OptimisticLockException exception) {
            throw ApplicationException.of(CommonStatusCode.CONCURRENT_UPDATE, exception);
        }
        groupEntity.updateFrom(group);
        return saveGroupAndDetails(group, groupEntity);
    }

    private CommonCodeGroup saveGroupAndDetails(CommonCodeGroup group, CommonCodeGroupJpaEntity groupEntity) {
        CommonCodeGroupJpaEntity savedGroup;
        try {
            savedGroup = commonCodeGroupRepository.saveAndFlush(groupEntity);
        } catch (DataIntegrityViolationException exception) {
            throw translateGroupConstraintViolation(exception);
        }
        Map<String, CommonCodeJpaEntity> existingDetails = commonCodeRepository
                .findAllByGroupCode(group.groupCode())
                .stream()
                .collect(Collectors.toMap(
                        entity -> entity.getId().getCode(),
                        Function.identity()
                ));

        Map<String, CommonCodeJpaEntity> detailsToSave = new LinkedHashMap<>(existingDetails);
        group.details().forEach(detail -> detailsToSave.put(
                detail.code(),
                updateOrCreateDetail(detail, existingDetails)
        ));
        deactivateRemovedDetails(group, existingDetails);
        commonCodeRepository.saveAllAndFlush(detailsToSave.values());

        CommonCodeGroup result = CommonCodeGroupMapper.toDomain(savedGroup, findDetails(group.groupCode()));
        evictCachesAfterCommit();
        return result;
    }

    private CommonCodeJpaEntity updateOrCreateDetail(
            CommonCode detail,
            Map<String, CommonCodeJpaEntity> existingDetails
    ) {
        CommonCodeJpaEntity existing = existingDetails.get(detail.code());
        if (existing != null) {
            existing.updateFrom(detail);
            return existing;
        }
        return new CommonCodeJpaEntity(detail);
    }

    private void deactivateRemovedDetails(
            CommonCodeGroup group,
            Map<String, CommonCodeJpaEntity> existingDetails
    ) {
        var requestedCodes = group.details().stream()
                .map(CommonCode::code)
                .collect(Collectors.toSet());
        existingDetails.forEach((code, entity) -> {
            if (!requestedCodes.contains(code)) {
                entity.deactivate();
            }
        });
    }

    private List<CommonCode> findDetails(String groupCode) {
        return commonCodeRepository.findAllByGroupCode(groupCode).stream()
                .map(CommonCodeMapper::toDomain)
                .toList();
    }

    private void evictCachesAfterCommit() {
        if (TransactionSynchronizationManager.isSynchronizationActive()
                && TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    evictCaches();
                }
            });
            return;
        }
        evictCaches();
    }

    private void evictCaches() {
        CACHE_NAMES.stream()
                .map(cacheManager::getCache)
                .filter(cache -> cache != null)
                .forEach(Cache::clear);
    }

    private RuntimeException translateGroupConstraintViolation(DataIntegrityViolationException exception) {
        if (hasConstraint(exception, "common_code_group_pkey")) {
            return ApplicationException.of(
                    CommonCodeStatusCode.COMMON_CODE_GROUP_ALREADY_EXISTS,
                    exception
            );
        }
        return exception;
    }

    private boolean hasConstraint(Throwable throwable, String constraintName) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof ConstraintViolationException constraintViolation
                    && constraintName.equals(constraintViolation.getConstraintName())) {
                return true;
            }
            if (current.getMessage() != null && current.getMessage().contains(constraintName)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
