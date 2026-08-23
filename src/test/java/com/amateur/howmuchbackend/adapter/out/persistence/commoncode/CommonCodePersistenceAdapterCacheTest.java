package com.amateur.howmuchbackend.adapter.out.persistence.commoncode;

import com.amateur.howmuchbackend.adapter.out.persistence.commoncode.entity.CommonCodeGroupJpaEntity;
import com.amateur.howmuchbackend.adapter.out.persistence.commoncode.entity.CommonCodeJpaEntity;
import com.amateur.howmuchbackend.adapter.out.persistence.commoncode.repository.CommonCodeGroupJpaRepository;
import com.amateur.howmuchbackend.adapter.out.persistence.commoncode.repository.CommonCodeJpaRepository;
import com.amateur.howmuchbackend.application.port.out.commoncode.LoadCommonCodePort;
import com.amateur.howmuchbackend.application.port.out.commoncode.SaveCommonCodeGroupPort;
import com.amateur.howmuchbackend.domain.commoncode.CommonCode;
import com.amateur.howmuchbackend.domain.commoncode.CommonCodeGroup;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringJUnitConfig(CommonCodePersistenceAdapterCacheTest.CacheTestConfiguration.class)
@DisplayName("공통 코드 조회 캐시")
class CommonCodePersistenceAdapterCacheTest {
    @Autowired
    private LoadCommonCodePort loadCommonCodePort;

    @Autowired
    private SaveCommonCodeGroupPort saveCommonCodeGroupPort;

    @Autowired
    private CommonCodeJpaRepository commonCodeRepository;

    @Autowired
    private CommonCodeGroupJpaRepository commonCodeGroupRepository;

    @Autowired
    private CacheManager cacheManager;

    @BeforeEach
    void setUp() {
        cacheManager.getCacheNames().forEach(name -> cacheManager.getCache(name).clear());
        reset(commonCodeRepository, commonCodeGroupRepository);
    }

    @Test
    @DisplayName("활성 단건·목록·존재 여부 조회를 각각 캐시한다")
    void cachesAllActiveReadPaths() {
        CommonCodeJpaEntity detail = new CommonCodeJpaEntity(
                new CommonCode("BANK", "0020", "우리은행", null, 1, null, null, null, true)
        );
        when(commonCodeRepository.findActiveByGroupCodeAndCode("BANK", "0020"))
                .thenReturn(Optional.of(detail));
        when(commonCodeRepository.findActiveByGroupCode("BANK"))
                .thenReturn(List.of(detail));
        when(commonCodeRepository.existsActiveByGroupCodeAndCode("BANK", "0020"))
                .thenReturn(true);

        loadCommonCodePort.findActiveByGroupCodeAndCode("BANK", "0020");
        loadCommonCodePort.findActiveByGroupCodeAndCode("BANK", "0020");
        loadCommonCodePort.findActiveByGroupCode("BANK");
        loadCommonCodePort.findActiveByGroupCode("BANK");
        loadCommonCodePort.existsActiveByGroupCodeAndCode("BANK", "0020");
        loadCommonCodePort.existsActiveByGroupCodeAndCode("BANK", "0020");

        verify(commonCodeRepository, times(1))
                .findActiveByGroupCodeAndCode("BANK", "0020");
        verify(commonCodeRepository, times(1)).findActiveByGroupCode("BANK");
        verify(commonCodeRepository, times(1))
                .existsActiveByGroupCodeAndCode("BANK", "0020");
    }

    @Test
    @DisplayName("그룹 저장 성공 후 활성 CommonCode 캐시를 비운다")
    void evictsCachesAfterSave() {
        CommonCodeJpaEntity detail = new CommonCodeJpaEntity(
                new CommonCode("BANK", "0020", "우리은행", null, 1, null, null, null, true)
        );
        when(commonCodeRepository.findActiveByGroupCode("BANK"))
                .thenReturn(List.of(detail));
        loadCommonCodePort.findActiveByGroupCode("BANK");

        when(commonCodeGroupRepository.findById("BANK")).thenReturn(Optional.empty());
        when(commonCodeGroupRepository.saveAndFlush(any(CommonCodeGroupJpaEntity.class)))
                .thenAnswer(invocation -> {
                    CommonCodeGroupJpaEntity entity = invocation.getArgument(0, CommonCodeGroupJpaEntity.class);
                    ReflectionTestUtils.setField(entity, "revision", 0L);
                    return entity;
                });
        when(commonCodeRepository.findAllByGroupCode("BANK")).thenReturn(List.of());

        saveCommonCodeGroupPort.save(
                CommonCodeGroup.create("BANK", "은행 코드", null, null, null, null, true)
        );
        loadCommonCodePort.findActiveByGroupCode("BANK");

        verify(commonCodeRepository, times(2)).findActiveByGroupCode("BANK");
        verify(commonCodeRepository, times(2)).findAllByGroupCode("BANK");
    }

    @Configuration
    @EnableCaching(proxyTargetClass = true)
    static class CacheTestConfiguration {
        @Bean
        CommonCodeGroupJpaRepository commonCodeGroupRepository() {
            return mock(CommonCodeGroupJpaRepository.class);
        }

        @Bean
        CommonCodeJpaRepository commonCodeRepository() {
            return mock(CommonCodeJpaRepository.class);
        }

        @Bean
        CacheManager cacheManager() {
            CaffeineCacheManager cacheManager = new CaffeineCacheManager(
                    "commonCodeByGroupAndCode",
                    "commonCodeByGroup",
                    "commonCodeExistsByGroupAndCode"
            );
            cacheManager.setCaffeine(Caffeine.newBuilder()
                    .maximumSize(1000)
                    .expireAfterWrite(Duration.ofMinutes(1)));
            return cacheManager;
        }

        @Bean
        CommonCodePersistenceAdapter commonCodePersistenceAdapter(
                CommonCodeGroupJpaRepository commonCodeGroupRepository,
                CommonCodeJpaRepository commonCodeRepository
        ) {
            return new CommonCodePersistenceAdapter(commonCodeGroupRepository, commonCodeRepository);
        }
    }
}
