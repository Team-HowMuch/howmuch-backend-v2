package com.amateur.howmuchbackend.application.port.in.commoncode;

import com.amateur.howmuchbackend.application.port.in.commoncode.command.CreateCommonCodeGroupCommand;
import com.amateur.howmuchbackend.application.port.in.commoncode.result.CommonCodeGroupResult;

public interface CreateCommonCodeGroupUseCase {
    CommonCodeGroupResult create(CreateCommonCodeGroupCommand command);
}
