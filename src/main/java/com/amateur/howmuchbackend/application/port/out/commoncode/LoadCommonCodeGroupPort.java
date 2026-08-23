package com.amateur.howmuchbackend.application.port.out.commoncode;

import com.amateur.howmuchbackend.domain.commoncode.CommonCodeGroup;
import com.amateur.howmuchbackend.domain.commoncode.CommonCodeGroupSummary;

import java.util.List;
import java.util.Optional;

public interface LoadCommonCodeGroupPort {
    List<CommonCodeGroupSummary> findAll(String keyword);

    Optional<CommonCodeGroup> findByGroupCode(String groupCode);
}
