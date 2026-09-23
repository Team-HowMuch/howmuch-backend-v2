package com.amateur.howmuchbackend.adapter.out.persistence.commoncode;

import com.amateur.howmuchbackend.adapter.out.persistence.commoncode.entity.CommonCodeGroupJpaEntity;
import com.amateur.howmuchbackend.adapter.out.persistence.commoncode.entity.CommonCodeJpaEntity;
import com.amateur.howmuchbackend.adapter.out.persistence.commoncode.repository.CommonCodeGroupJpaRepository;
import com.amateur.howmuchbackend.adapter.out.persistence.commoncode.repository.CommonCodeJpaRepository;
import com.amateur.howmuchbackend.application.port.out.commoncode.LoadCommonCodePort;
import com.amateur.howmuchbackend.application.port.out.commoncode.SaveCommonCodeGroupPort;
import com.amateur.howmuchbackend.domain.commoncode.CommonCode;
import com.amateur.howmuchbackend.domain.commoncode.CommonCodeGroup;
import com.amateur.howmuchbackend.infra.cache.config.CommonCodeCacheConfig;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringJUnitConfig({
        CommonCodeCacheConfig.class,
        CommonCodePersistenceAdapterCacheTest.CacheTestConfiguration.class
})
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

    @Autowired
    private MutableTicker ticker;

    @BeforeEach
    void setUp() {
        cacheManager.getCacheNames().forEach(name -> cacheManager.getCache(name).clear());
        reset(commonCodeRepository, commonCodeGroupRepository);
        ticker.reset();
    }

    @Test
    @DisplayName("활성 단건·목록·존재 여부 조회를 각각 캐시한다")
    void commonCodeCache_allActiveReadPaths_cached() {
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
    @DisplayName("TTL 만료 후 활성 CommonCode를 다시 조회한다")
    void reloadsAfterTtl() {
        CommonCodeJpaEntity detail = new CommonCodeJpaEntity(
                new CommonCode("BANK", "0020", "우리은행", null, 1, null, null, null, true)
        );
        when(commonCodeRepository.findActiveByGroupCode("BANK"))
                .thenReturn(List.of(detail));

        loadCommonCodePort.findActiveByGroupCode("BANK");
        ticker.advance(Duration.ofSeconds(59));
        loadCommonCodePort.findActiveByGroupCode("BANK");
        ticker.advance(Duration.ofSeconds(1));
        loadCommonCodePort.findActiveByGroupCode("BANK");

        verify(commonCodeRepository, times(2)).findActiveByGroupCode("BANK");
    }

    @Test
    @DisplayName("그룹 저장 성공 후 트랜잭션 커밋 시 활성 CommonCode 캐시를 비운다")
    void evictsCachesAfterCommit() {
        CommonCodeJpaEntity detail = new CommonCodeJpaEntity(
                new CommonCode("BANK", "0020", "우리은행", null, 1, null, null, null, true)
        );
        when(commonCodeRepository.findActiveByGroupCode("BANK"))
                .thenReturn(List.of(detail));
        loadCommonCodePort.findActiveByGroupCode("BANK");

        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            saveGroup();
            loadCommonCodePort.findActiveByGroupCode("BANK");
            verify(commonCodeRepository, times(1)).findActiveByGroupCode("BANK");

            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(TransactionSynchronization::afterCommit);
            loadCommonCodePort.findActiveByGroupCode("BANK");

            verify(commonCodeRepository, times(2)).findActiveByGroupCode("BANK");
            verify(commonCodeRepository, times(2)).findAllByGroupCode("BANK");
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    @Test
    @DisplayName("그룹 저장 트랜잭션 롤백 시 기존 활성 CommonCode 캐시를 유지한다")
    void keepsCachesAfterRollback() {
        CommonCodeJpaEntity detail = new CommonCodeJpaEntity(
                new CommonCode("BANK", "0020", "우리은행", null, 1, null, null, null, true)
        );
        when(commonCodeRepository.findActiveByGroupCode("BANK"))
                .thenReturn(List.of(detail));
        loadCommonCodePort.findActiveByGroupCode("BANK");

        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            saveGroup();
            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
            loadCommonCodePort.findActiveByGroupCode("BANK");

            verify(commonCodeRepository, times(1)).findActiveByGroupCode("BANK");
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    private void saveGroup() {

        when(commonCodeGroupRepository.saveAndFlush(any(CommonCodeGroupJpaEntity.class)))
                .thenAnswer(invocation -> {
                    CommonCodeGroupJpaEntity entity = invocation.getArgument(0, CommonCodeGroupJpaEntity.class);
                    ReflectionTestUtils.setField(entity, "revision", 0L);
                    return entity;
                });
        when(commonCodeRepository.findAllByGroupCode("BANK")).thenReturn(List.of());

        saveCommonCodeGroupPort.create(
                CommonCodeGroup.create("BANK", "은행 코드", null, null, null, null, true)
        );
    }

    @Configuration
    static class CacheTestConfiguration {
        @Bean
        MutableTicker ticker() {
            return new MutableTicker();
        }

        @Bean
        CommonCodeGroupJpaRepository commonCodeGroupRepository() {
            return mock(CommonCodeGroupJpaRepository.class);
        }

        @Bean
        CommonCodeJpaRepository commonCodeRepository() {
            return mock(CommonCodeJpaRepository.class);
        }

        @Bean
        CacheManager cacheManager(MutableTicker ticker) {
            CaffeineCacheManager cacheManager = new CaffeineCacheManager(
                    "commonCodeByGroupAndCode",
                    "commonCodeByGroup",
                    "commonCodeExistsByGroupAndCode"
            );
            cacheManager.setCaffeine(Caffeine.newBuilder()
                    .maximumSize(1000)
                    .expireAfterWrite(Duration.ofMinutes(1))
                    .ticker(ticker));
            return cacheManager;
        }

        @Bean
        CommonCodePersistenceAdapter commonCodePersistenceAdapter(
                CommonCodeGroupJpaRepository commonCodeGroupRepository,
                CommonCodeJpaRepository commonCodeRepository,
                CacheManager cacheManager
        ) {
            return new CommonCodePersistenceAdapter(commonCodeGroupRepository, commonCodeRepository, cacheManager,
                    mock(jakarta.persistence.EntityManager.class));
        }
    }

    static class MutableTicker implements Ticker {
        private final AtomicLong nanos = new AtomicLong();

        @Override
        public long read() {
            return nanos.get();
        }

        void advance(Duration duration) {
            nanos.addAndGet(duration.toNanos());
        }

        void reset() {
            nanos.set(0L);
        }
    }
}
