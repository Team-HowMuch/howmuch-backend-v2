package com.amateur.howmuchbackend.adapter.in.web.settlement;

import com.amateur.howmuchbackend.adapter.in.web.common.ApiResponse;
import com.amateur.howmuchbackend.adapter.in.web.settlement.docs.SettlementExpenseDocs;
import com.amateur.howmuchbackend.adapter.in.web.settlement.dto.request.CreateSettlementExpenseWebRequest;
import com.amateur.howmuchbackend.adapter.in.web.settlement.dto.request.UpdateSettlementExpenseWebRequest;
import com.amateur.howmuchbackend.adapter.in.web.settlement.dto.response.SettlementExpenseListWebResponse;
import com.amateur.howmuchbackend.adapter.in.web.settlement.dto.response.SettlementExpenseMutationWebResponse;
import com.amateur.howmuchbackend.adapter.in.web.settlement.dto.response.SettlementExpenseTotalWebResponse;
import com.amateur.howmuchbackend.application.port.in.settlement.CreateSettlementExpenseUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.GetSettlementExpensesUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.RemoveSettlementExpenseUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.UpdateSettlementExpenseUseCase;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v2/settlements/{settlementId}/expenses")
public class SettlementExpenseController implements SettlementExpenseDocs {
    private final CreateSettlementExpenseUseCase createSettlementExpenseUseCase;
    private final GetSettlementExpensesUseCase getSettlementExpensesUseCase;
    private final UpdateSettlementExpenseUseCase updateSettlementExpenseUseCase;
    private final RemoveSettlementExpenseUseCase removeSettlementExpenseUseCase;

    @Override
    @PostMapping
    public ApiResponse<SettlementExpenseMutationWebResponse> createSettlementExpense(
            @PathVariable UUID settlementId,
            @Valid @RequestBody CreateSettlementExpenseWebRequest request
    ) {
        return ApiResponse.success(SettlementExpenseMutationWebResponse.from(
                createSettlementExpenseUseCase.create(settlementId, request.toCommand())
        ));
    }

    @Override
    @GetMapping
    public ApiResponse<SettlementExpenseListWebResponse> getSettlementExpenses(
            @PathVariable UUID settlementId
    ) {
        return ApiResponse.success(SettlementExpenseListWebResponse.from(
                getSettlementExpensesUseCase.get(settlementId)
        ));
    }

    @Override
    @PatchMapping("/{expenseId}")
    public ApiResponse<SettlementExpenseMutationWebResponse> updateSettlementExpense(
            @PathVariable UUID settlementId,
            @PathVariable UUID expenseId,
            @Valid @RequestBody UpdateSettlementExpenseWebRequest request
    ) {
        return ApiResponse.success(SettlementExpenseMutationWebResponse.from(
                updateSettlementExpenseUseCase.update(
                        settlementId,
                        expenseId,
                        request.toCommand()
                )
        ));
    }

    @Override
    @DeleteMapping("/{expenseId}")
    public ApiResponse<SettlementExpenseTotalWebResponse> removeSettlementExpense(
            @PathVariable UUID settlementId,
            @PathVariable UUID expenseId
    ) {
        return ApiResponse.success(SettlementExpenseTotalWebResponse.from(
                removeSettlementExpenseUseCase.remove(settlementId, expenseId)
        ));
    }
}
