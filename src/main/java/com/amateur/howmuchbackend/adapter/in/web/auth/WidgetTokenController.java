package com.amateur.howmuchbackend.adapter.in.web.auth;

import com.amateur.howmuchbackend.adapter.in.web.auth.docs.WidgetTokenDocs;
import com.amateur.howmuchbackend.adapter.in.web.auth.dto.response.WidgetTokenWebResponse;
import com.amateur.howmuchbackend.adapter.in.web.common.ApiResponse;
import com.amateur.howmuchbackend.application.port.in.auth.IssueWidgetTokenUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class WidgetTokenController implements WidgetTokenDocs {
    private final IssueWidgetTokenUseCase useCase;

    @Override
    @PostMapping(value = "/api/v2/auth/widget-token", produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<WidgetTokenWebResponse> issue() {
        return ApiResponse.success(WidgetTokenWebResponse.from(useCase.issue()));
    }
}
