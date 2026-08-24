package com.amateur.howmuchbackend.adapter.in.web.settlement;

import com.amateur.howmuchbackend.adapter.in.web.common.ApiResponse;
import com.amateur.howmuchbackend.adapter.in.web.settlement.docs.SettlementItemAllocationDocs;
import com.amateur.howmuchbackend.adapter.in.web.settlement.dto.request.SaveSettlementItemSelectionWebRequest;
import com.amateur.howmuchbackend.adapter.in.web.settlement.dto.response.SettlementItemAllocationWebResponse;
import com.amateur.howmuchbackend.application.port.in.settlement.GetSettlementItemAllocationUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.RemoveSettlementItemSelectionUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.SaveSettlementItemSelectionUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.SubmitSettlementItemAllocationUseCase;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v2/settlement-links/{token}/participants/{participantId}")
public class SettlementItemAllocationController implements SettlementItemAllocationDocs {
    private static final String PARTICIPANT_CONTEXT_HEADER =
            "Link-Participant-Context";

    private final GetSettlementItemAllocationUseCase getUseCase;
    private final SaveSettlementItemSelectionUseCase saveUseCase;
    private final RemoveSettlementItemSelectionUseCase removeUseCase;
    private final SubmitSettlementItemAllocationUseCase submitUseCase;

    @Override
    @GetMapping("/items")
    public ApiResponse<SettlementItemAllocationWebResponse> get(
            @PathVariable String token,
            @PathVariable UUID participantId,
            @RequestHeader(PARTICIPANT_CONTEXT_HEADER) UUID participantContext
    ) {
        return ApiResponse.success(SettlementItemAllocationWebResponse.from(
                getUseCase.get(token, participantContext, participantId)
        ));
    }

    @Override
    @PutMapping("/items/{itemId}")
    public ApiResponse<SettlementItemAllocationWebResponse> save(
            @PathVariable String token,
            @PathVariable UUID participantId,
            @PathVariable UUID itemId,
            @RequestHeader(PARTICIPANT_CONTEXT_HEADER) UUID participantContext,
            @Valid @RequestBody SaveSettlementItemSelectionWebRequest request
    ) {
        return ApiResponse.success(SettlementItemAllocationWebResponse.from(
                saveUseCase.save(
                        token,
                        participantContext,
                        participantId,
                        itemId,
                        request.toCommand()
                )
        ));
    }

    @Override
    @DeleteMapping("/items/{itemId}")
    public ApiResponse<SettlementItemAllocationWebResponse> remove(
            @PathVariable String token,
            @PathVariable UUID participantId,
            @PathVariable UUID itemId,
            @RequestHeader(PARTICIPANT_CONTEXT_HEADER) UUID participantContext
    ) {
        return ApiResponse.success(SettlementItemAllocationWebResponse.from(
                removeUseCase.remove(
                        token,
                        participantContext,
                        participantId,
                        itemId
                )
        ));
    }

    @Override
    @PostMapping("/item-submission")
    public ApiResponse<SettlementItemAllocationWebResponse> submit(
            @PathVariable String token,
            @PathVariable UUID participantId,
            @RequestHeader(PARTICIPANT_CONTEXT_HEADER) UUID participantContext
    ) {
        return ApiResponse.success(SettlementItemAllocationWebResponse.from(
                submitUseCase.submit(token, participantContext, participantId)
        ));
    }
}
