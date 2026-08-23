package com.amateur.howmuchbackend.adapter.in.web.commoncode.dto.request;

import com.amateur.howmuchbackend.application.port.in.commoncode.command.CreateCommonCodeGroupCommand;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateCommonCodeGroupWebRequest(
        @NotBlank @Size(max = 50) String groupCode,
        @NotBlank @Size(max = 100) String groupName,
        @Size(max = 500) String description,
        @Size(max = 50) String attr1Name,
        @Size(max = 50) String attr2Name,
        @Size(max = 50) String attr3Name,
        @NotNull Boolean active
) {
    public CreateCommonCodeGroupCommand toCommand() {
        return new CreateCommonCodeGroupCommand(
                groupCode,
                groupName,
                description,
                attr1Name,
                attr2Name,
                attr3Name,
                active
        );
    }
}
