package com.amateur.howmuchbackend.adapter.in.web.settlement;

import com.amateur.howmuchbackend.adapter.in.web.common.ApiResponse;
import com.amateur.howmuchbackend.adapter.in.web.settlement.docs.SettlementPaymentExpenseDocs;
import com.amateur.howmuchbackend.adapter.in.web.settlement.dto.request.ImportSettlementPaymentExpenseWebRequest;
import com.amateur.howmuchbackend.adapter.in.web.settlement.dto.response.SettlementExpenseMutationWebResponse;
import com.amateur.howmuchbackend.adapter.in.web.settlement.dto.response.SettlementPaymentExpenseCandidateWebResponse;
import com.amateur.howmuchbackend.application.port.in.settlement.GetSettlementPaymentExpenseCandidatesUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.ImportSettlementPaymentExpenseUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v2/settlements/{settlementId}")
public class SettlementPaymentExpenseController implements SettlementPaymentExpenseDocs {
    private final GetSettlementPaymentExpenseCandidatesUseCase getCandidatesUseCase;
    private final ImportSettlementPaymentExpenseUseCase importExpenseUseCase;

    @Override
    @GetMapping("/expense-candidates/payment-history")
    public ApiResponse<List<SettlementPaymentExpenseCandidateWebResponse>> getPaymentExpenseCandidates(
            @PathVariable UUID settlementId,
            @RequestParam Long accountId,
            @RequestParam(required = false) YearMonth yearMonth
    ) {
        return ApiResponse.success(getCandidatesUseCase.get(
                        settlementId,
                        accountId,
                        yearMonth
                ).stream()
                .map(SettlementPaymentExpenseCandidateWebResponse::from)
                .toList());
    }

    @Override
    @PostMapping("/expenses/payment-history")
    public ApiResponse<SettlementExpenseMutationWebResponse> importPaymentExpense(
            @PathVariable UUID settlementId,
            @RequestBody ImportSettlementPaymentExpenseWebRequest request
    ) {
        return ApiResponse.success(SettlementExpenseMutationWebResponse.from(
                importExpenseUseCase.importExpense(settlementId, request.toCommand())
        ));
    }
}
