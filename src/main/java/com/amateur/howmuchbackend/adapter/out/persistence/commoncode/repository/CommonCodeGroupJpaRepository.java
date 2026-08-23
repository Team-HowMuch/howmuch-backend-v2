package com.amateur.howmuchbackend.adapter.out.persistence.commoncode.repository;

import com.amateur.howmuchbackend.adapter.out.persistence.commoncode.entity.CommonCodeGroupJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface CommonCodeGroupJpaRepository extends JpaRepository<CommonCodeGroupJpaEntity, String> {
    List<CommonCodeGroupJpaEntity> findAllByOrderByGroupCodeAsc();

    @Query("""
            select groupCode
              from CommonCodeGroupJpaEntity groupCode
             where lower(groupCode.groupCode) like lower(concat('%', :keyword, '%'))
                or lower(groupCode.groupName) like lower(concat('%', :keyword, '%'))
             order by groupCode.groupCode asc
            """)
    List<CommonCodeGroupJpaEntity> findAllByKeyword(@Param("keyword") String keyword);
}
