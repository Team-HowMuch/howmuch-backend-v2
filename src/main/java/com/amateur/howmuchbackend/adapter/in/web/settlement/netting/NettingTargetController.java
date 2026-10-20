package com.amateur.howmuchbackend.adapter.in.web.settlement.netting;

import com.amateur.howmuchbackend.adapter.in.web.common.ApiResponse;
import com.amateur.howmuchbackend.adapter.in.web.settlement.netting.dto.response.*;
import com.amateur.howmuchbackend.application.port.in.settlement.netting.*;

import lombok.RequiredArgsConstructor;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v2")
@ConditionalOnProperty(
        prefix = "howmuch.settlement.netting",
        name = "enabled",
        havingValue = "true")
public class NettingTargetController implements NettingTargetDocs {
    private final GetGroupNettingsUseCase nettings;

    @GetMapping("/settlement-nettings/{nettingId}")
    public ApiResponse<GroupNettingWebResponse> get(@PathVariable UUID nettingId) {
        return ApiResponse.success(GroupNettingWebResponse.from(nettings.getById(nettingId)));
    }

    @GetMapping("/settlements/{settlementId}/netting")
    public ApiResponse<GroupNettingWebResponse> source(@PathVariable UUID settlementId) {
        return ApiResponse.success(
                GroupNettingWebResponse.from(nettings.getBySource(settlementId)));
    }
}
