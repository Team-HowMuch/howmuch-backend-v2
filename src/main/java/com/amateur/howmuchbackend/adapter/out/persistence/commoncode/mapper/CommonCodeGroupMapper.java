package com.amateur.howmuchbackend.adapter.out.persistence.commoncode.mapper;

import com.amateur.howmuchbackend.adapter.out.persistence.commoncode.entity.CommonCodeGroupJpaEntity;
import com.amateur.howmuchbackend.domain.commoncode.CommonCode;
import com.amateur.howmuchbackend.domain.commoncode.CommonCodeGroup;
import com.amateur.howmuchbackend.domain.commoncode.CommonCodeGroupSummary;

import java.util.List;

public final class CommonCodeGroupMapper {
    private CommonCodeGroupMapper() {
    }

    public static CommonCodeGroup toDomain(
            CommonCodeGroupJpaEntity entity,
            List<CommonCode> details
    ) {
        return new CommonCodeGroup(
                entity.getGroupCode(),
                entity.getGroupName(),
                entity.getDescription(),
                entity.getAttr1Name(),
                entity.getAttr2Name(),
                entity.getAttr3Name(),
                entity.isActive(),
                entity.getRevision(),
                details
        );
    }

    public static CommonCodeGroupSummary toSummary(
            CommonCodeGroupJpaEntity entity,
            long detailCount
    ) {
        return new CommonCodeGroupSummary(
                entity.getGroupCode(),
                entity.getGroupName(),
                entity.getDescription(),
                entity.isActive(),
                entity.getRevision(),
                detailCount
        );
    }
}
