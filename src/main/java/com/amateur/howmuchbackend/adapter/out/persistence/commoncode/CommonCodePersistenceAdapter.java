package com.amateur.howmuchbackend.adapter.out.persistence.commoncode;

import com.amateur.howmuchbackend.adapter.out.persistence.commoncode.mapper.CommonCodeMapper;
import com.amateur.howmuchbackend.adapter.out.persistence.commoncode.repository.CommonCodeJpaRepository;
import com.amateur.howmuchbackend.application.port.out.commoncode.LoadCommonCodePort;
import com.amateur.howmuchbackend.domain.commoncode.CommonCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class CommonCodePersistenceAdapter implements LoadCommonCodePort {
    private final CommonCodeJpaRepository commonCodeRepository;

    @Override
    @Transactional(readOnly = true)
    public Optional<CommonCode> findActiveByGroupCodeAndCode(String groupCode, String code) {
        return commonCodeRepository.findActiveByGroupCodeAndCode(groupCode, code)
                .map(CommonCodeMapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<CommonCode> findActiveByGroupCode(String groupCode) {
        return commonCodeRepository.findActiveByGroupCode(groupCode).stream()
                .map(CommonCodeMapper::toDomain)
                .toList();
    }
}
