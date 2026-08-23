package com.amateur.howmuchbackend.adapter.in.web.commoncode.dto.request;

import com.amateur.howmuchbackend.application.port.in.commoncode.command.UpdateCommonCodeGroupCommand;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record UpdateCommonCodeGroupWebRequest(
        @NotNull Long revision,
        @NotBlank @Size(max = 100) String groupName,
        @Size(max = 500) String description,
        @Size(max = 50) String attr1Name,
        @Size(max = 50) String attr2Name,
        @Size(max = 50) String attr3Name,
        @NotNull Boolean active,
        @NotNull List<@NotNull @Valid CommonCodeDetailWebRequest> details
) {
    public UpdateCommonCodeGroupCommand toCommand() {
        return new UpdateCommonCodeGroupCommand(
                revision,
                groupName,
                description,
                attr1Name,
                attr2Name,
                attr3Name,
                active,
                details.stream().map(CommonCodeDetailWebRequest::toCommand).toList()
        );
    }
}
