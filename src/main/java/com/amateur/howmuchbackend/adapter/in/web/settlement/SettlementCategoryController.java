package com.amateur.howmuchbackend.adapter.in.web.settlement;

import com.amateur.howmuchbackend.adapter.in.web.common.ApiResponse;
import com.amateur.howmuchbackend.adapter.in.web.settlement.docs.SettlementCategoryDocs;
import com.amateur.howmuchbackend.adapter.in.web.settlement.dto.response.SettlementCategoryWebResponse;
import com.amateur.howmuchbackend.application.port.in.settlement.GetSettlementCategoriesUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v2/settlement-categories")
public class SettlementCategoryController implements SettlementCategoryDocs {
    private final GetSettlementCategoriesUseCase categories;

    @GetMapping
    @Override
    public ApiResponse<List<SettlementCategoryWebResponse>> getCategories() {
        return ApiResponse.success(
                categories.getCategories().stream().map(SettlementCategoryWebResponse::from).toList());
    }
}
