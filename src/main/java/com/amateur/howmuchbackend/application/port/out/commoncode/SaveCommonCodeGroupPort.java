package com.amateur.howmuchbackend.application.port.out.commoncode;

import com.amateur.howmuchbackend.domain.commoncode.CommonCodeGroup;

public interface SaveCommonCodeGroupPort {
    /** Creates a new group; an existing group must never be changed. */
    CommonCodeGroup create(CommonCodeGroup group);

    /** Updates an existing group; a missing group must never be created. */
    CommonCodeGroup update(CommonCodeGroup group);
}
