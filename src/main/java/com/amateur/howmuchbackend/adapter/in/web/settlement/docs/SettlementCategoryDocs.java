package com.amateur.howmuchbackend.adapter.in.web.settlement.docs;

import com.amateur.howmuchbackend.adapter.in.web.common.ApiResponse;
import com.amateur.howmuchbackend.adapter.in.web.settlement.dto.response.SettlementCategoryWebResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.util.List;

@Tag(name = "정산 카테고리", description = "정산 카테고리 목록과 내 카테고리 API")
public interface SettlementCategoryDocs {
    @Operation(
            summary = "정산 카테고리 목록 조회",
            description = "정산에서 쓰는 카테고리를 표시 순서대로 반환합니다. 미분류(UNCATEGORIZED)를 포함합니다. "
                    + "서버 공통 코드로 관리하므로 앱에 고정하지 말고 이 목록으로 표시명·아이콘을 그립니다. "
                    + "icon은 아이콘 키이며 앱이 아이콘 이미지에 연결합니다. 로그인 없이 호출할 수 있습니다."
    )
    ApiResponse<List<SettlementCategoryWebResponse>> getCategories();
}
