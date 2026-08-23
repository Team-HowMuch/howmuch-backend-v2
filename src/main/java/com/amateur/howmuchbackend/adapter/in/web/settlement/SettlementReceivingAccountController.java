package com.amateur.howmuchbackend.adapter.in.web.settlement;

import com.amateur.howmuchbackend.adapter.in.web.common.ApiResponse;
import com.amateur.howmuchbackend.adapter.in.web.settlement.docs.SettlementReceivingAccountDocs;
import com.amateur.howmuchbackend.adapter.in.web.settlement.dto.request.SelectSettlementReceivingAccountWebRequest;
import com.amateur.howmuchbackend.adapter.in.web.settlement.dto.response.SettlementReceivingAccountWebResponse;
import com.amateur.howmuchbackend.adapter.in.web.settlement.dto.response.SettlementWebResponse;
import com.amateur.howmuchbackend.application.port.in.settlement.GetSettlementReceivingAccountsUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.SelectSettlementReceivingAccountUseCase;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v2/settlements")
public class SettlementReceivingAccountController implements SettlementReceivingAccountDocs {
    private final GetSettlementReceivingAccountsUseCase getReceivingAccountsUseCase;
    private final SelectSettlementReceivingAccountUseCase selectReceivingAccountUseCase;

    @Override
    @GetMapping("/{settlementId}/receiving-accounts")
    public ApiResponse<List<SettlementReceivingAccountWebResponse>> getReceivingAccounts(
            @PathVariable UUID settlementId
    ) {
        return ApiResponse.success(getReceivingAccountsUseCase.get(settlementId).stream()
                .map(SettlementReceivingAccountWebResponse::from)
                .toList());
    }

    @Override
    @PutMapping("/{settlementId}/receiving-account")
    public ApiResponse<SettlementWebResponse> selectReceivingAccount(
            @PathVariable UUID settlementId,
            @Valid @RequestBody SelectSettlementReceivingAccountWebRequest request
    ) {
        return ApiResponse.success(SettlementWebResponse.from(
                selectReceivingAccountUseCase.select(settlementId, request.toCommand())
        ));
    }
}
