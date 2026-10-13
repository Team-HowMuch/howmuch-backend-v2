package com.amateur.howmuchbackend.adapter.in.web.settlement;

import com.amateur.howmuchbackend.adapter.in.web.common.ApiResponse;
import com.amateur.howmuchbackend.adapter.in.web.settlement.docs.SettlementReceiptExpenseDocs;
import com.amateur.howmuchbackend.adapter.in.web.settlement.dto.request.CreateSettlementReceiptExpenseWebRequest;
import com.amateur.howmuchbackend.adapter.in.web.settlement.dto.request.ImportSettlementReceiptExpenseWebRequest;
import com.amateur.howmuchbackend.adapter.in.web.settlement.dto.response.SettlementItemListWebResponse;
import com.amateur.howmuchbackend.adapter.in.web.settlement.dto.response.SettlementReceiptExpenseMutationWebResponse;
import com.amateur.howmuchbackend.application.port.in.settlement.CreateSettlementReceiptExpenseUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.GetSettlementItemsUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.ImportSettlementReceiptExpenseUseCase;
import com.amateur.howmuchbackend.adapter.in.web.settlement.dto.request.ReplaceSettlementReceiptExpenseWebRequest;
import com.amateur.howmuchbackend.application.port.in.settlement.ReplaceSettlementReceiptExpenseUseCase;
import org.springframework.web.bind.annotation.PutMapping;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v2/settlements/{settlementId}")
public class SettlementReceiptExpenseController implements SettlementReceiptExpenseDocs {
    private final CreateSettlementReceiptExpenseUseCase createSettlementReceiptExpenseUseCase;
    private final ImportSettlementReceiptExpenseUseCase importSettlementReceiptExpenseUseCase;
    private final GetSettlementItemsUseCase getSettlementItemsUseCase;
    private final ReplaceSettlementReceiptExpenseUseCase replaceSettlementReceiptExpenseUseCase;

    @Override
    @PostMapping("/expenses/receipt")
    public ApiResponse<SettlementReceiptExpenseMutationWebResponse> createReceiptExpense(
            @PathVariable UUID settlementId,
            @Valid @RequestBody CreateSettlementReceiptExpenseWebRequest request
    ) {
        return ApiResponse.success(SettlementReceiptExpenseMutationWebResponse.from(
                createSettlementReceiptExpenseUseCase.create(settlementId, request.toCommand())
        ));
    }

    @Override
    @PutMapping("/expenses/receipt/{expenseId}")
    public ApiResponse<SettlementReceiptExpenseMutationWebResponse> replaceReceiptExpense(
            @PathVariable UUID settlementId, @PathVariable UUID expenseId,
            @Valid @RequestBody ReplaceSettlementReceiptExpenseWebRequest request
    ) {
        return ApiResponse.success(SettlementReceiptExpenseMutationWebResponse.from(
                replaceSettlementReceiptExpenseUseCase.replace(settlementId, expenseId, request.toCommand())));
    }

    @Override
    @PostMapping("/expenses/receipt-ocr")
    public ApiResponse<SettlementReceiptExpenseMutationWebResponse> importReceiptExpense(
            @PathVariable UUID settlementId,
            @Valid @RequestBody ImportSettlementReceiptExpenseWebRequest request
    ) {
        return ApiResponse.success(SettlementReceiptExpenseMutationWebResponse.from(
                importSettlementReceiptExpenseUseCase.importExpense(
                        settlementId,
                        request.toCommand()
                )
        ));
    }

    @Override
    @GetMapping("/items")
    public ApiResponse<SettlementItemListWebResponse> getSettlementItems(
            @PathVariable UUID settlementId
    ) {
        return ApiResponse.success(SettlementItemListWebResponse.from(
                getSettlementItemsUseCase.get(settlementId)
        ));
    }
}
