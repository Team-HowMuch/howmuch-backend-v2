package com.amateur.howmuchbackend.adapter.in.web.commoncode.dto.request;

import com.amateur.howmuchbackend.application.port.in.commoncode.command.SearchCommonCodeGroupsCommand;

public record CommonCodeGroupSearchWebRequest(String keyword) {
    public SearchCommonCodeGroupsCommand toCommand() {
        return new SearchCommonCodeGroupsCommand(keyword);
    }
}
