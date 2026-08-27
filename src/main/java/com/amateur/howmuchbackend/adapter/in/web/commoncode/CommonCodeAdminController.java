package com.amateur.howmuchbackend.adapter.in.web.commoncode;

import com.amateur.howmuchbackend.adapter.in.web.common.ApiResponse;
import com.amateur.howmuchbackend.adapter.in.web.commoncode.docs.CommonCodeAdminDocs;
import com.amateur.howmuchbackend.adapter.in.web.commoncode.dto.request.CommonCodeGroupSearchWebRequest;
import com.amateur.howmuchbackend.adapter.in.web.commoncode.dto.request.CreateCommonCodeGroupWebRequest;
import com.amateur.howmuchbackend.adapter.in.web.commoncode.dto.request.UpdateCommonCodeGroupWebRequest;
import com.amateur.howmuchbackend.adapter.in.web.commoncode.dto.response.CommonCodeGroupSummaryWebResponse;
import com.amateur.howmuchbackend.adapter.in.web.commoncode.dto.response.CommonCodeGroupWebResponse;
import com.amateur.howmuchbackend.application.port.in.commoncode.CreateCommonCodeGroupUseCase;
import com.amateur.howmuchbackend.application.port.in.commoncode.GetCommonCodeGroupUseCase;
import com.amateur.howmuchbackend.application.port.in.commoncode.SearchCommonCodeGroupsUseCase;
import com.amateur.howmuchbackend.application.port.in.commoncode.UpdateCommonCodeGroupUseCase;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v2/admin/common-codes/groups")
public class CommonCodeAdminController implements CommonCodeAdminDocs {
    private final SearchCommonCodeGroupsUseCase searchCommonCodeGroupsUseCase;
    private final GetCommonCodeGroupUseCase getCommonCodeGroupUseCase;
    private final CreateCommonCodeGroupUseCase createCommonCodeGroupUseCase;
    private final UpdateCommonCodeGroupUseCase updateCommonCodeGroupUseCase;

    @Override
    @GetMapping
    public ApiResponse<List<CommonCodeGroupSummaryWebResponse>> searchGroups(
            @Valid @ModelAttribute CommonCodeGroupSearchWebRequest request
    ) {
        return ApiResponse.success(searchCommonCodeGroupsUseCase.search(request.toCommand()).stream()
                .map(CommonCodeGroupSummaryWebResponse::from)
                .toList());
    }

    @Override
    @GetMapping("/{groupCode}")
    public ApiResponse<CommonCodeGroupWebResponse> getGroup(@PathVariable String groupCode) {
        return ApiResponse.success(CommonCodeGroupWebResponse.from(
                getCommonCodeGroupUseCase.get(groupCode)
        ));
    }

    @Override
    @PostMapping
    public ApiResponse<CommonCodeGroupWebResponse> createGroup(
            @Valid @RequestBody CreateCommonCodeGroupWebRequest request
    ) {
        return ApiResponse.created(CommonCodeGroupWebResponse.from(
                createCommonCodeGroupUseCase.create(request.toCommand())
        ));
    }

    @Override
    @PutMapping("/{groupCode}")
    public ApiResponse<CommonCodeGroupWebResponse> updateGroup(
            @PathVariable String groupCode,
            @Valid @RequestBody UpdateCommonCodeGroupWebRequest request
    ) {
        return ApiResponse.success(CommonCodeGroupWebResponse.from(
                updateCommonCodeGroupUseCase.update(groupCode, request.toCommand())
        ));
    }
}
