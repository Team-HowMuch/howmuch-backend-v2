package com.amateur.howmuchbackend.adapter.in.web.group;

import com.amateur.howmuchbackend.adapter.in.web.common.ApiResponse;
import com.amateur.howmuchbackend.adapter.in.web.group.docs.GroupSettlementDocs;
import com.amateur.howmuchbackend.adapter.in.web.group.dto.request.GroupSettlementListWebRequest;
import com.amateur.howmuchbackend.adapter.in.web.group.dto.response.GroupSettlementListItemWebResponse;
import com.amateur.howmuchbackend.application.port.in.settlement.GetGroupSettlementsUseCase;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v2/groups/{groupId}/settlements")
public class GroupSettlementController implements GroupSettlementDocs {
    private final GetGroupSettlementsUseCase getGroupSettlementsUseCase;

    @Override
    @GetMapping
    public ApiResponse<List<GroupSettlementListItemWebResponse>> get(
            @PathVariable Long groupId, @ModelAttribute GroupSettlementListWebRequest request
    ) {
        return ApiResponse.success(getGroupSettlementsUseCase.get(request.toCommand(groupId)).stream()
                .map(GroupSettlementListItemWebResponse::from).toList());
    }
}
