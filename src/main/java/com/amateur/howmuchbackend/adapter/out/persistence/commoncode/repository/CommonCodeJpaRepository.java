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
            order by commonCode.sortOrder asc, commonCode.id.code asc
           """)
    List<CommonCodeJpaEntity> findAllByGroupCode(@Param("groupCode") String groupCode);

    @Query("""
           select commonCode.id.groupCode as groupCode, count(commonCode) as detailCount
             from CommonCodeJpaEntity commonCode
            where commonCode.id.groupCode in :groupCodes
            group by commonCode.id.groupCode
           """)
    List<GroupDetailCountProjection> countByGroupCodes(@Param("groupCodes") List<String> groupCodes);

    @Query("""
            select commonCode
              from CommonCodeJpaEntity commonCode
             where commonCode.id.groupCode = :groupCode
               and commonCode.id.code = :code
               and commonCode.active = true
               and exists (
                   select groupCode
                     from CommonCodeGroupJpaEntity groupCode
                    where groupCode.groupCode = commonCode.id.groupCode
                      and groupCode.active = true
               )
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
               and exists (
                   select groupCode
                     from CommonCodeGroupJpaEntity groupCode
                    where groupCode.groupCode = commonCode.id.groupCode
                      and groupCode.active = true
               )
             order by commonCode.sortOrder asc, commonCode.id.code asc
            """)
    List<CommonCodeJpaEntity> findActiveByGroupCode(@Param("groupCode") String groupCode);

    @Query("""
           select case when count(1) > 0 then true else false end
             from CommonCodeJpaEntity commonCode
            where commonCode.id.groupCode = :groupCode
              and commonCode.id.code = :code
              and commonCode.active = true
              and exists (
                  select groupCode
                    from CommonCodeGroupJpaEntity groupCode
                   where groupCode.groupCode = commonCode.id.groupCode
                     and groupCode.active = true
              )
    """)
    boolean existsActiveByGroupCodeAndCode(
            @Param("groupCode") String groupCode,
            @Param("code") String code
    );

    interface GroupDetailCountProjection {
        String getGroupCode();

        long getDetailCount();
    }
}
