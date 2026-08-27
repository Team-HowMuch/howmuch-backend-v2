package com.amateur.howmuchbackend.adapter.out.persistence.commoncode.mapper;

import com.amateur.howmuchbackend.adapter.out.persistence.commoncode.entity.CommonCodeJpaEntity;
import com.amateur.howmuchbackend.domain.commoncode.CommonCode;

public final class CommonCodeMapper {
    private CommonCodeMapper() {
    }

    public static CommonCode toDomain(CommonCodeJpaEntity entity) {
        return new CommonCode(
                entity.getId().getGroupCode(),
                entity.getId().getCode(),
                entity.getName(),
                entity.getDescription(),
                entity.getSortOrder(),
                entity.getAttr1(),
                entity.getAttr2(),
                entity.getAttr3(),
                entity.isActive()
        );
    }
}
