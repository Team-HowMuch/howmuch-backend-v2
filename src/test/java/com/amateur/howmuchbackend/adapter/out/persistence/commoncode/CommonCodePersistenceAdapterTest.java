package com.amateur.howmuchbackend.adapter.out.persistence.commoncode;

import com.amateur.howmuchbackend.adapter.out.persistence.commoncode.entity.CommonCodeGroupJpaEntity;
import com.amateur.howmuchbackend.adapter.out.persistence.commoncode.repository.CommonCodeGroupJpaRepository;
import com.amateur.howmuchbackend.adapter.out.persistence.commoncode.repository.CommonCodeJpaRepository;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.commoncode.CommonCodeGroup;
import com.amateur.howmuchbackend.domain.commoncode.status.CommonCodeStatusCode;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.CacheManager;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.SQLException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("공통 코드 Persistence Adapter")
class CommonCodePersistenceAdapterTest {
    @Mock
    private CommonCodeGroupJpaRepository groupRepository;

    @Mock
    private CommonCodeJpaRepository detailRepository;

    @Mock
    private CacheManager cacheManager;

    @Test
    @DisplayName("동시 그룹 생성으로 발생한 primary key 충돌을 409로 변환한다")
    void translatesConcurrentGroupCreation() {
        var adapter = new CommonCodePersistenceAdapter(groupRepository, detailRepository, cacheManager);
        when(groupRepository.findById("TEST")).thenReturn(Optional.empty());
        when(groupRepository.saveAndFlush(any(CommonCodeGroupJpaEntity.class))).thenThrow(
                new DataIntegrityViolationException(
                        "duplicate key",
                        new ConstraintViolationException(
                                "duplicate key",
                                new SQLException("duplicate key"),
                                "common_code_group_pkey"
                        )
                )
        );

        var group = CommonCodeGroup.create("TEST", "테스트", null, null, null, null, true);

        assertThatThrownBy(() -> adapter.save(group))
                .isInstanceOfSatisfying(ApplicationException.class, exception ->
                        org.assertj.core.api.Assertions.assertThat(exception.getStatusCode())
                                .isEqualTo(CommonCodeStatusCode.COMMON_CODE_GROUP_ALREADY_EXISTS));
        verifyNoInteractions(detailRepository);
    }
}
