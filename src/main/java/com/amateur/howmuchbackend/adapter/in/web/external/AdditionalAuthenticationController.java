package com.amateur.howmuchbackend.adapter.in.web.external;

import com.amateur.howmuchbackend.adapter.in.web.common.ApiResponse;
import com.amateur.howmuchbackend.adapter.in.web.external.docs.AdditionalAuthenticationDocs;
import com.amateur.howmuchbackend.adapter.in.web.external.dto.request.SubmitAdditionalAuthenticationSmsWebRequest;
import com.amateur.howmuchbackend.adapter.in.web.external.dto.response.AdditionalAuthenticationWebResponse;
import com.amateur.howmuchbackend.application.port.in.external.*;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v2/finance-authentications")
@ConditionalOnProperty(
        prefix = "howmuch.codef.additional-auth",
        name = "enabled",
        havingValue = "true")
public class AdditionalAuthenticationController implements AdditionalAuthenticationDocs {
    private final GetAdditionalAuthenticationUseCase query;
    private final SubmitAdditionalAuthenticationSmsUseCase submission;
    private final CancelAdditionalAuthenticationUseCase cancellation;

    private final ListAdditionalAuthenticationsUseCase lists;

    @GetMapping
    public ApiResponse<java.util.List<AdditionalAuthenticationWebResponse>> list() {
        return ApiResponse.success(
                lists.list().stream().map(AdditionalAuthenticationWebResponse::from).toList());
    }

    @GetMapping("/{id}")
    public ApiResponse<AdditionalAuthenticationWebResponse> get(@PathVariable UUID id) {
        return ApiResponse.success(AdditionalAuthenticationWebResponse.from(query.get(id)));
    }

    @PostMapping("/{id}/sms")
    public ApiResponse<AdditionalAuthenticationWebResponse> submit(
            @PathVariable UUID id,
            @Valid @RequestBody SubmitAdditionalAuthenticationSmsWebRequest request) {
        return ApiResponse.of(
                202,
                AdditionalAuthenticationWebResponse.from(submission.submit(request.toCommand(id))));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<AdditionalAuthenticationWebResponse> cancel(@PathVariable UUID id) {
        return ApiResponse.success(
                AdditionalAuthenticationWebResponse.from(cancellation.cancel(id)));
    }
}
