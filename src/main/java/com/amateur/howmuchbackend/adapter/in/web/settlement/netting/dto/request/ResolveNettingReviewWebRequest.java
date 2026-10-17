package com.amateur.howmuchbackend.adapter.in.web.settlement.netting.dto.request;

import com.amateur.howmuchbackend.application.port.in.settlement.netting.command.ResolveNettingReviewCommand;

import jakarta.validation.constraints.*;

import java.util.UUID;

public record ResolveNettingReviewWebRequest(
        @NotBlank @Pattern(regexp = "APPLIED_TO_NETTING|UNRELATED|RETURNED") String decision,
        UUID transferId,
        @NotBlank @Size(max = 1000) String proof) {
    public ResolveNettingReviewCommand toCommand() {
        return new ResolveNettingReviewCommand(decision, transferId, proof);
    }
}
