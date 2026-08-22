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
import com.amateur.howmuchbackend.domain.commoncode.CommonCode;
import com.amateur.howmuchbackend.domain.commoncode.CommonCodeGroup;
import com.amateur.howmuchbackend.domain.commoncode.CommonCodeGroupSummary;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

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
    private final CommonCodeGroupJpaRepository commonCodeGroupRepository;
    private final CommonCodeJpaRepository commonCodeRepository;

    @Override
    public Optional<CommonCode> findActiveByGroupCodeAndCode(String groupCode, String code) {
        return commonCodeRepository.findActiveByGroupCodeAndCode(groupCode, code)
                .map(CommonCodeMapper::toDomain);
    }

    @Override
    public List<CommonCode> findActiveByGroupCode(String groupCode) {
        return commonCodeRepository.findActiveByGroupCode(groupCode).stream()
                .map(CommonCodeMapper::toDomain)
                .toList();
    }

    @Override
    public boolean existsActiveByGroupCodeAndCode(String groupCode, String code) {
        return commonCodeRepository.existsActiveByGroupCodeAndCode(groupCode, code);
    }

    @Override
    public List<CommonCodeGroupSummary> findAll(String keyword) {
        List<CommonCodeGroupJpaEntity> groups = keyword == null || keyword.isBlank()
                ? commonCodeGroupRepository.findAllByOrderByGroupCodeAsc()
                : commonCodeGroupRepository.findAllByKeyword(keyword.trim());
        return groups.stream()
                .map(group -> CommonCodeGroupMapper.toSummary(
                        group,
                        commonCodeRepository.countByGroupCode(group.getGroupCode())
                ))
                .toList();
    }

    @Override
    public Optional<CommonCodeGroup> findByGroupCode(String groupCode) {
        return commonCodeGroupRepository.findById(groupCode)
                .map(group -> CommonCodeGroupMapper.toDomain(group, findDetails(groupCode)));
    }

    @Override
    public CommonCodeGroup save(CommonCodeGroup group) {
        CommonCodeGroupJpaEntity groupEntity = commonCodeGroupRepository.findById(group.groupCode())
                .map(existing -> {
                    existing.updateFrom(group);
                    return existing;
                })
                .orElseGet(() -> new CommonCodeGroupJpaEntity(group));

        CommonCodeGroupJpaEntity savedGroup = commonCodeGroupRepository.saveAndFlush(groupEntity);
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

        return CommonCodeGroupMapper.toDomain(savedGroup, findDetails(group.groupCode()));
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
}
