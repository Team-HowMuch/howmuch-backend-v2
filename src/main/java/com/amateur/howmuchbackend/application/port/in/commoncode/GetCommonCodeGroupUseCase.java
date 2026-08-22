package com.amateur.howmuchbackend.application.port.in.commoncode;

import com.amateur.howmuchbackend.application.port.in.commoncode.result.CommonCodeGroupResult;

public interface GetCommonCodeGroupUseCase {
    CommonCodeGroupResult get(String groupCode);
}
