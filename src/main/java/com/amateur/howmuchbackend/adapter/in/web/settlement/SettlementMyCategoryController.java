package com.amateur.howmuchbackend.adapter.in.web.settlement;

import com.amateur.howmuchbackend.adapter.in.web.common.ApiResponse;
import com.amateur.howmuchbackend.adapter.in.web.settlement.docs.SettlementMyCategoryDocs;
import com.amateur.howmuchbackend.adapter.in.web.settlement.dto.request.ChangeMySettlementCategoryWebRequest;
import com.amateur.howmuchbackend.adapter.in.web.settlement.dto.response.SettlementMyCategoryWebResponse;
import com.amateur.howmuchbackend.application.port.in.settlement.ChangeMySettlementCategoryUseCase;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v2/settlements")
public class SettlementMyCategoryController implements SettlementMyCategoryDocs {
    private final ChangeMySettlementCategoryUseCase changeMyCategoryUseCase;

    @Override
    @PutMapping("/{settlementId}/my-category")
    public ApiResponse<SettlementMyCategoryWebResponse> changeMyCategory(
            @PathVariable UUID settlementId,
            @Valid @RequestBody ChangeMySettlementCategoryWebRequest request
    ) {
        return ApiResponse.success(SettlementMyCategoryWebResponse.from(
                changeMyCategoryUseCase.change(settlementId, request.toCommand())
        ));
    }
}
