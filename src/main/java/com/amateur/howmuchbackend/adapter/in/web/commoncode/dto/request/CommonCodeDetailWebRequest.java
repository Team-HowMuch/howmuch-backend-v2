package com.amateur.howmuchbackend.adapter.in.web.commoncode.dto.request;

import com.amateur.howmuchbackend.application.port.in.commoncode.command.CommonCodeDetailCommand;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record CommonCodeDetailWebRequest(
        @NotBlank @Size(max = 50) String code,
        @NotBlank @Size(max = 100) String name,
        @Size(max = 500) String description,
        @NotNull @PositiveOrZero Integer sortOrder,
        @Size(max = 255) String attr1,
        @Size(max = 255) String attr2,
        @Size(max = 255) String attr3,
        @NotNull Boolean active
) {
    public CommonCodeDetailCommand toCommand() {
        return new CommonCodeDetailCommand(
                code,
                name,
                description,
                sortOrder,
                attr1,
                attr2,
                attr3,
                active
        );
    }
}
