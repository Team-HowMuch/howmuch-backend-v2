package com.amateur.howmuchbackend.adapter.in.web.settlement;

import com.amateur.howmuchbackend.adapter.in.web.common.ApiResponse;
import com.amateur.howmuchbackend.adapter.in.web.settlement.docs.SettlementDocs;
import com.amateur.howmuchbackend.adapter.in.web.settlement.dto.request.AddSettlementParticipantWebRequest;
import com.amateur.howmuchbackend.adapter.in.web.settlement.dto.request.CreateSettlementWebRequest;
import com.amateur.howmuchbackend.adapter.in.web.settlement.dto.request.MatchSettlementContactsWebRequest;
import com.amateur.howmuchbackend.adapter.in.web.settlement.dto.request.SearchSettlementParticipantWebRequest;
import com.amateur.howmuchbackend.adapter.in.web.settlement.dto.request.SettlementListWebRequest;
import com.amateur.howmuchbackend.adapter.in.web.settlement.dto.request.UpdateSettlementDraftWebRequest;
import com.amateur.howmuchbackend.adapter.in.web.settlement.dto.response.SettlementParticipantCandidateWebResponse;
import com.amateur.howmuchbackend.adapter.in.web.settlement.dto.response.SettlementContactCandidateWebResponse;
import com.amateur.howmuchbackend.adapter.in.web.settlement.dto.response.SettlementDetailWebResponse;
import com.amateur.howmuchbackend.adapter.in.web.settlement.dto.response.SettlementParticipantListItemWebResponse;
import com.amateur.howmuchbackend.adapter.in.web.settlement.dto.response.SettlementParticipantWebResponse;
import com.amateur.howmuchbackend.adapter.in.web.settlement.dto.response.SettlementListItemWebResponse;
import com.amateur.howmuchbackend.adapter.in.web.settlement.dto.response.SettlementWebResponse;
import com.amateur.howmuchbackend.application.port.in.settlement.AddSettlementParticipantUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.CreateSettlementUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.DeleteSettlementUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.GetSettlementParticipantAvatarUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.GetSettlementParticipantCandidateAvatarUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.GetSettlementGroupParticipantCandidatesUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.GetSettlementUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.GetSettlementParticipantsUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.GetSettlementsUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.MatchSettlementContactsUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.RemoveSettlementParticipantUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.ResumeSettlementUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.SearchSettlementParticipantUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.SuspendSettlementUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.UpdateSettlementDraftUseCase;
import com.amateur.howmuchbackend.application.user.model.ProfileImage;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v2/settlements")
public class SettlementController implements SettlementDocs {
    private final CreateSettlementUseCase createSettlementUseCase;
    private final DeleteSettlementUseCase deleteSettlementUseCase;
    private final GetSettlementUseCase getSettlementUseCase;
    private final GetSettlementsUseCase getSettlementsUseCase;
    private final UpdateSettlementDraftUseCase updateSettlementDraftUseCase;
    private final AddSettlementParticipantUseCase addSettlementParticipantUseCase;
    private final RemoveSettlementParticipantUseCase removeSettlementParticipantUseCase;
    private final SuspendSettlementUseCase suspendSettlementUseCase;
    private final ResumeSettlementUseCase resumeSettlementUseCase;
    private final GetSettlementParticipantsUseCase getSettlementParticipantsUseCase;
    private final GetSettlementParticipantAvatarUseCase getSettlementParticipantAvatarUseCase;
    private final SearchSettlementParticipantUseCase searchSettlementParticipantUseCase;
    private final GetSettlementParticipantCandidateAvatarUseCase getSettlementParticipantCandidateAvatarUseCase;
    private final MatchSettlementContactsUseCase matchSettlementContactsUseCase;
    private final GetSettlementGroupParticipantCandidatesUseCase getSettlementGroupParticipantCandidatesUseCase;

    @Override
    @PostMapping
    public ApiResponse<SettlementWebResponse> createSettlement(
            @Valid @RequestBody CreateSettlementWebRequest request
    ) {
        return ApiResponse.success(SettlementWebResponse.from(
                createSettlementUseCase.create(request.toCommand())
        ));
    }

    @Override
    @DeleteMapping("/{settlementId}")
    public ApiResponse<Void> deleteSettlement(
            @PathVariable UUID settlementId
    ) {
        deleteSettlementUseCase.delete(settlementId);
        return ApiResponse.success();
    }

    @Override
    @GetMapping("/{settlementId}")
    public ApiResponse<SettlementDetailWebResponse> getSettlement(
            @PathVariable UUID settlementId
    ) {
        return ApiResponse.success(SettlementDetailWebResponse.from(
                getSettlementUseCase.get(settlementId)
        ));
    }

    @Override
    @GetMapping
    public ApiResponse<List<SettlementListItemWebResponse>> getSettlements(
            @Valid @ModelAttribute SettlementListWebRequest request
    ) {
        return ApiResponse.success(getSettlementsUseCase.get(request.toCommand())
                .stream()
                .map(SettlementListItemWebResponse::from)
                .toList());
    }

    @Override
    @PatchMapping("/{settlementId}")
    public ApiResponse<SettlementWebResponse> updateSettlement(
            @PathVariable UUID settlementId,
            @Valid @RequestBody UpdateSettlementDraftWebRequest request
    ) {
        return ApiResponse.success(SettlementWebResponse.from(
                updateSettlementDraftUseCase.update(settlementId, request.toCommand())
        ));
    }

    @Override
    @PostMapping("/{settlementId}/suspend")
    public ApiResponse<SettlementWebResponse> suspendSettlement(
            @PathVariable UUID settlementId
    ) {
        return ApiResponse.success(SettlementWebResponse.from(
                suspendSettlementUseCase.suspend(settlementId)
        ));
    }

    @Override
    @PostMapping("/{settlementId}/resume")
    public ApiResponse<SettlementWebResponse> resumeSettlement(
            @PathVariable UUID settlementId
    ) {
        return ApiResponse.success(SettlementWebResponse.from(
                resumeSettlementUseCase.resume(settlementId)
        ));
    }

    @Override
    @PostMapping("/{settlementId}/participants")
    public ApiResponse<SettlementParticipantWebResponse> addSettlementParticipant(
            @PathVariable UUID settlementId,
            @Valid @RequestBody AddSettlementParticipantWebRequest request
    ) {
        return ApiResponse.success(SettlementParticipantWebResponse.from(
                addSettlementParticipantUseCase.add(settlementId, request.toCommand())
        ));
    }

    @Override
    @GetMapping("/{settlementId}/participants")
    public ApiResponse<List<SettlementParticipantListItemWebResponse>> getSettlementParticipants(
            @PathVariable UUID settlementId
    ) {
        return ApiResponse.success(getSettlementParticipantsUseCase.get(settlementId)
                .stream()
                .map(result -> SettlementParticipantListItemWebResponse.from(settlementId, result))
                .toList());
    }

    @Override
    @GetMapping("/{settlementId}/participant-candidates/users")
    public ApiResponse<List<SettlementParticipantCandidateWebResponse>> searchSettlementParticipantCandidates(
            @PathVariable UUID settlementId,
            @Valid @ModelAttribute SearchSettlementParticipantWebRequest request
    ) {
        return ApiResponse.success(searchSettlementParticipantUseCase
                .searchByPublicId(settlementId, request.toCommand())
                .stream()
                .map(result -> SettlementParticipantCandidateWebResponse.from(settlementId, result))
                .toList());
    }

    @Override
    @GetMapping("/{settlementId}/participant-candidates/users/{publicId}/avatar")
    public ResponseEntity<byte[]> getSettlementParticipantCandidateAvatar(
            @PathVariable UUID settlementId,
            @PathVariable String publicId
    ) {
        ProfileImage image = getSettlementParticipantCandidateAvatarUseCase.get(
                settlementId,
                publicId
        );
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.parseMediaType(image.contentType()))
                .body(image.content());
    }

    @Override
    @PostMapping("/{settlementId}/participant-candidates/contacts/match")
    public ApiResponse<List<SettlementContactCandidateWebResponse>> matchSettlementContacts(
            @PathVariable UUID settlementId,
            @Valid @RequestBody MatchSettlementContactsWebRequest request
    ) {
        return ApiResponse.success(matchSettlementContactsUseCase.match(
                        settlementId,
                        request.toCommand()
                ).stream()
                .map(result -> SettlementContactCandidateWebResponse.from(settlementId, result))
                .toList());
    }

    @Override
    @GetMapping("/{settlementId}/participant-candidates/groups/{groupId}/members")
    public ApiResponse<List<SettlementParticipantCandidateWebResponse>> getSettlementGroupParticipantCandidates(
            @PathVariable UUID settlementId,
            @PathVariable Long groupId
    ) {
        return ApiResponse.success(getSettlementGroupParticipantCandidatesUseCase
                .get(settlementId, groupId)
                .stream()
                .map(result -> SettlementParticipantCandidateWebResponse.from(settlementId, result))
                .toList());
    }

    @Override
    @GetMapping("/{settlementId}/participants/{participantId}/avatar")
    public ResponseEntity<byte[]> getSettlementParticipantAvatar(
            @PathVariable UUID settlementId,
            @PathVariable UUID participantId
    ) {
        ProfileImage image = getSettlementParticipantAvatarUseCase.get(settlementId, participantId);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.parseMediaType(image.contentType()))
                .body(image.content());
    }

    @Override
    @DeleteMapping("/{settlementId}/participants/{participantId}")
    public ApiResponse<Void> removeSettlementParticipant(
            @PathVariable UUID settlementId,
            @PathVariable UUID participantId
    ) {
        removeSettlementParticipantUseCase.remove(settlementId, participantId);
        return ApiResponse.success();
    }

}
