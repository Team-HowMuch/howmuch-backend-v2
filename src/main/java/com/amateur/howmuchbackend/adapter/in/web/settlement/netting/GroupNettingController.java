package com.amateur.howmuchbackend.adapter.in.web.settlement.netting;

import com.amateur.howmuchbackend.adapter.in.web.common.ApiResponse;
import com.amateur.howmuchbackend.adapter.in.web.settlement.netting.dto.request.*;
import com.amateur.howmuchbackend.adapter.in.web.settlement.netting.dto.response.*;
import com.amateur.howmuchbackend.application.port.in.settlement.netting.*;

import lombok.RequiredArgsConstructor;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v2/groups/{groupId}")
@ConditionalOnProperty(
        prefix = "howmuch.settlement.netting",
        name = "enabled",
        havingValue = "true")
public class GroupNettingController implements GroupNettingDocs {
    private final ExecuteGroupNettingUseCase executions;
    private final GetGroupNettingEstimateUseCase estimates;
    private final GetGroupNettingsUseCase nettings;
    private final GetNettedTransferInstructionUseCase instructions;
    private final ReconcileNettedTransfersUseCase reconciliation;
    private final RemindNettedTransferUseCase reminders;

    @PostMapping("/settlement-nettings")
    public ApiResponse<NettingExecutionWebResponse> execute(
            @PathVariable long groupId, @RequestHeader("Idempotency-Key") UUID key) {
        return ApiResponse.of(
                202,
                NettingExecutionWebResponse.from(
                        executions.execute(
                                groupId,
                                new com.amateur.howmuchbackend.application.port.in.settlement
                                        .netting.command.ExecuteGroupNettingCommand(key))));
    }

    @GetMapping("/settlement-netting-executions/{executionId}")
    public ApiResponse<NettingExecutionWebResponse> getExecution(
            @PathVariable long groupId, @PathVariable UUID executionId) {
        return ApiResponse.success(
                NettingExecutionWebResponse.from(executions.getExecution(groupId, executionId)));
    }

    @GetMapping("/settlement-nettings")
    public ApiResponse<List<GroupNettingWebResponse>> list(@PathVariable long groupId) {
        return ApiResponse.success(
                nettings.list(groupId).stream().map(GroupNettingWebResponse::from).toList());
    }

    @GetMapping("/settlement-nettings/estimate")
    public ApiResponse<GroupNettingEstimateWebResponse> estimate(@PathVariable long groupId) {
        return ApiResponse.success(
                GroupNettingEstimateWebResponse.from(estimates.estimate(groupId)));
    }

    @GetMapping("/settlement-nettings/current")
    public ApiResponse<GroupNettingWebResponse> current(@PathVariable long groupId) {
        return ApiResponse.success(GroupNettingWebResponse.from(nettings.current(groupId)));
    }

    @GetMapping("/settlement-nettings/{nettingId}")
    public ApiResponse<GroupNettingWebResponse> get(
            @PathVariable long groupId, @PathVariable UUID nettingId) {
        return ApiResponse.success(GroupNettingWebResponse.from(nettings.get(groupId, nettingId)));
    }

    @GetMapping("/settlement-nettings/{nettingId}/transfers/{transferId}/instruction")
    public ApiResponse<NettedTransferInstructionWebResponse> instruction(
            @PathVariable long groupId,
            @PathVariable UUID nettingId,
            @PathVariable UUID transferId) {
        return ApiResponse.success(
                NettedTransferInstructionWebResponse.from(
                        instructions.getInstruction(groupId, nettingId, transferId)));
    }

    @PostMapping("/settlement-nettings/{nettingId}/deposits/reconcile")
    public ApiResponse<NettingExecutionWebResponse> reconcile(
            @PathVariable long groupId,
            @PathVariable UUID nettingId,
            @RequestHeader("Idempotency-Key") UUID key) {
        return ApiResponse.of(
                202,
                NettingExecutionWebResponse.from(
                        reconciliation.reconcile(groupId, nettingId, key)));
    }

    @PostMapping("/settlement-nettings/{nettingId}/transfers/{transferId}/reminders")
    public ApiResponse<Void> remind(
            @PathVariable long groupId,
            @PathVariable UUID nettingId,
            @PathVariable UUID transferId) {
        reminders.remind(groupId, nettingId, transferId);
        return ApiResponse.of(202);
    }
}
