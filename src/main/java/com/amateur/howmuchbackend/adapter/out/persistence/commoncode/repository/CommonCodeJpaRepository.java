package com.amateur.howmuchbackend.adapter.out.persistence.commoncode.repository;

import com.amateur.howmuchbackend.adapter.out.persistence.commoncode.entity.CommonCodeJpaEntity;
import com.amateur.howmuchbackend.adapter.out.persistence.commoncode.entity.CommonCodeJpaId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface CommonCodeJpaRepository extends JpaRepository<CommonCodeJpaEntity, CommonCodeJpaId> {
    @Query("""
            select commonCode
              from CommonCodeJpaEntity commonCode
             where commonCode.id.groupCode = :groupCode
               and commonCode.id.code = :code
               and commonCode.active = true
            """)
    Optional<CommonCodeJpaEntity> findActiveByGroupCodeAndCode(
            @Param("groupCode") String groupCode,
            @Param("code") String code
    );

    @Query("""
            select commonCode
              from CommonCodeJpaEntity commonCode
             where commonCode.id.groupCode = :groupCode
               and commonCode.active = true
             order by commonCode.sortOrder asc, commonCode.id.code asc
            """)
    List<CommonCodeJpaEntity> findActiveByGroupCode(@Param("groupCode") String groupCode);
}
