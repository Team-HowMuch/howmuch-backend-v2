package com.amateur.howmuchbackend.adapter.out.persistence.commoncode;

import com.amateur.howmuchbackend.adapter.out.persistence.commoncode.repository.CommonCodeJpaRepository;
import com.amateur.howmuchbackend.application.port.out.commoncode.LoadCommonCodePort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest
@DisplayName("공통 코드 애플리케이션 캐시 설정")
class CommonCodeCacheApplicationContextTest {
    @Autowired
    private CacheManager cacheManager;

    @Autowired
    private LoadCommonCodePort loadCommonCodePort;

    @MockitoBean
    private CommonCodeJpaRepository commonCodeRepository;

    @Test
    @DisplayName("실제 애플리케이션 Caffeine CacheManager와 1분 TTL을 사용한다")
    void commonCodeCache_applicationConfiguration_usesOneMinuteTtl() {
        assertThat(cacheManager).isInstanceOf(CaffeineCacheManager.class);

        CaffeineCache cache = (CaffeineCache) cacheManager.getCache("commonCodeByGroup");
        long expirySeconds = cache.getNativeCache()
                .policy()
                .expireAfterWrite()
                .orElseThrow()
                .getExpiresAfter(TimeUnit.SECONDS);
        assertThat(expirySeconds).isEqualTo(60L);

        when(commonCodeRepository.findActiveByGroupCode("BANK")).thenReturn(List.of());
        loadCommonCodePort.findActiveByGroupCode("BANK");
        loadCommonCodePort.findActiveByGroupCode("BANK");

        verify(commonCodeRepository, times(1)).findActiveByGroupCode("BANK");
    }
}
