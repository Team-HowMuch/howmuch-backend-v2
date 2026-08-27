package com.amateur.howmuchbackend.application.commoncode.service;

import com.amateur.howmuchbackend.application.port.out.commoncode.LoadCommonCodePort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.commoncode.CommonCode;
import com.amateur.howmuchbackend.domain.commoncode.status.CommonCodeStatusCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CommonCodeService {
    private final LoadCommonCodePort loadCommonCodePort;

    public void validate(String groupCode, String code) {
        if (!loadCommonCodePort.existsActiveByGroupCodeAndCode(groupCode, code)) {
            throw ApplicationException.of(CommonCodeStatusCode.INVALID_COMMON_CODE);
        }
    }

    public Map<String, CommonCode> getMap(String groupCode) {
        return loadCommonCodePort.findActiveByGroupCode(groupCode).stream()
                .collect(Collectors.toUnmodifiableMap(
                        CommonCode::code,
                        Function.identity()
                ));
    }
}
