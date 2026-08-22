package com.amateur.howmuchbackend.application.port.in.commoncode;

import com.amateur.howmuchbackend.application.port.in.commoncode.command.SearchCommonCodeGroupsCommand;
import com.amateur.howmuchbackend.application.port.in.commoncode.result.CommonCodeGroupSummaryResult;

import java.util.List;

public interface SearchCommonCodeGroupsUseCase {
    List<CommonCodeGroupSummaryResult> search(SearchCommonCodeGroupsCommand command);
}
