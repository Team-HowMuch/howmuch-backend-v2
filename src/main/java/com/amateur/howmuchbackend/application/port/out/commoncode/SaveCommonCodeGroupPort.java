package com.amateur.howmuchbackend.application.port.out.commoncode;

import com.amateur.howmuchbackend.domain.commoncode.CommonCodeGroup;

public interface SaveCommonCodeGroupPort {
    CommonCodeGroup save(CommonCodeGroup group);
}
