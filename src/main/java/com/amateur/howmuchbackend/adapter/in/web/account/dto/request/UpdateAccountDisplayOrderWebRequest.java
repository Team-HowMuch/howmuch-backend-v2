package com.amateur.howmuchbackend.adapter.in.web.account.dto.request;

import com.amateur.howmuchbackend.application.port.in.account.command.UpdateAccountDisplayOrderCommand;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record UpdateAccountDisplayOrderWebRequest(
        @NotNull
        @PositiveOrZero
        Integer displayOrder
) {
    public UpdateAccountDisplayOrderCommand toCommand(Long accountId) {
        return new UpdateAccountDisplayOrderCommand(accountId, displayOrder);
    }
}
