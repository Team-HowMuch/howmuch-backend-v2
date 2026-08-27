package com.amateur.howmuchbackend.application.port.in.commoncode;

import com.amateur.howmuchbackend.application.port.in.commoncode.command.UpdateCommonCodeGroupCommand;
import com.amateur.howmuchbackend.application.port.in.commoncode.result.CommonCodeGroupResult;

public interface UpdateCommonCodeGroupUseCase {
    CommonCodeGroupResult update(String groupCode, UpdateCommonCodeGroupCommand command);
}
