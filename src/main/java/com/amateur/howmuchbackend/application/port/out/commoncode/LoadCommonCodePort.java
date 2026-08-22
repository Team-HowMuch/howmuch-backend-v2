package com.amateur.howmuchbackend.application.port.out.commoncode;

import com.amateur.howmuchbackend.domain.commoncode.CommonCode;

import java.util.List;
import java.util.Optional;

public interface LoadCommonCodePort {
    Optional<CommonCode> findActiveByGroupCodeAndCode(String groupCode, String code);

    List<CommonCode> findActiveByGroupCode(String groupCode);

    boolean existsActiveByGroupCodeAndCode(String groupCode, String code);
}
