package com.amateur.howmuchbackend.adapter.in.web.settlement.docs;

import com.amateur.howmuchbackend.adapter.in.web.common.ApiResponse;
import com.amateur.howmuchbackend.adapter.in.web.settlement.dto.request.AddSettlementParticipantWebRequest;
import com.amateur.howmuchbackend.adapter.in.web.settlement.dto.request.CreateSettlementWebRequest;
import com.amateur.howmuchbackend.adapter.in.web.settlement.dto.request.MatchSettlementContactsWebRequest;
import com.amateur.howmuchbackend.adapter.in.web.settlement.dto.request.SearchSettlementParticipantWebRequest;
import com.amateur.howmuchbackend.adapter.in.web.settlement.dto.request.UpdateSettlementDraftWebRequest;
import com.amateur.howmuchbackend.adapter.in.web.settlement.dto.response.SettlementParticipantCandidateWebResponse;
import com.amateur.howmuchbackend.adapter.in.web.settlement.dto.response.SettlementContactCandidateWebResponse;
import com.amateur.howmuchbackend.adapter.in.web.settlement.dto.response.SettlementParticipantListItemWebResponse;
import com.amateur.howmuchbackend.adapter.in.web.settlement.dto.response.SettlementParticipantWebResponse;
import com.amateur.howmuchbackend.adapter.in.web.settlement.dto.response.SettlementWebResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springdoc.core.annotations.ParameterObject;

import java.util.List;
import java.util.UUID;

@Tag(name = "정산 API", description = "정산 초안과 참여자 관리 API")
public interface SettlementDocs {
    @Operation(
            summary = "정산 초안 생성",
            description = "현재 로그인한 사용자를 생성자로 하는 정산 초안을 생성합니다. "
                    + "제목 또는 총액이 유효하지 않으면 INVALID_ARGUMENT을 반환합니다.",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "정산 초안 생성 성공",
                    useReturnTypeSchema = true
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "INVALID_ARGUMENT - 요청값 검증 실패"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "UNAUTHORIZED - 인증 필요"
            )
    })
    ApiResponse<SettlementWebResponse> createSettlement(CreateSettlementWebRequest request);

    @Operation(
            summary = "정산 초안 조회",
            description = "현재 로그인한 사용자가 생성한 정산을 ID로 조회합니다. "
                    + "정산이 없으면 SETTLEMENT_NOT_FOUND, 다른 생성자의 정산이면 SETTLEMENT_ACCESS_DENIED를 반환합니다.",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "정산 초안 조회 성공",
                    useReturnTypeSchema = true
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "UNAUTHORIZED - 인증 필요"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403",
                    description = "SETTLEMENT_ACCESS_DENIED - 접근 권한 없음"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "SETTLEMENT_NOT_FOUND - 정산 없음"
            )
    })
    ApiResponse<SettlementWebResponse> getSettlement(UUID settlementId);

    @Operation(
            summary = "정산 초안 수정",
            description = "현재 로그인한 생성자가 DRAFT 상태 정산의 제목 또는 총액을 수정합니다. "
                    + "정산이 없으면 SETTLEMENT_NOT_FOUND, 다른 생성자의 정산이면 SETTLEMENT_ACCESS_DENIED, "
                    + "DRAFT가 아니면 SETTLEMENT_NOT_EDITABLE, 다른 요청이 먼저 수정했으면 CONCURRENT_UPDATE, "
                    + "입력이 유효하지 않으면 INVALID_ARGUMENT을 반환합니다.",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "정산 초안 수정 성공",
                    useReturnTypeSchema = true
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "INVALID_ARGUMENT - 요청값 검증 실패"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "UNAUTHORIZED - 인증 필요"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403",
                    description = "SETTLEMENT_ACCESS_DENIED - 접근 권한 없음"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "SETTLEMENT_NOT_FOUND - 정산 없음"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409",
                    description = "SETTLEMENT_NOT_EDITABLE - 초안 상태가 아님; "
                            + "CONCURRENT_UPDATE - 다른 요청이 먼저 정산을 수정함"
            )
    })
    ApiResponse<SettlementWebResponse> updateSettlement(
            UUID settlementId,
            UpdateSettlementDraftWebRequest request
    );

    @Operation(
            summary = "정산 참여자 추가",
            description = "현재 로그인한 생성자가 DRAFT 정산에 등록 사용자, 연락처 또는 그룹 구성원 참여자를 추가합니다. "
                    + "등록 사용자는 publicId만, 연락처는 displayName과 phoneNumber만, "
                    + "그룹 구성원은 groupId와 publicId만 전달해야 합니다. "
                    + "연락처 원본 전화번호는 회원 매칭과 HMAC 변환 후 저장하거나 응답하지 않습니다.",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "정산 참여자 추가 성공",
                    useReturnTypeSchema = true
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "INVALID_ARGUMENT, INVALID_PUBLIC_ID, INVALID_PHONE_NUMBER 또는 "
                            + "INVALID_SETTLEMENT_PARTICIPANT_ADD_REQUEST - 입력 오류"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "UNAUTHORIZED - 인증 필요"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403",
                    description = "SETTLEMENT_ACCESS_DENIED 또는 SETTLEMENT_GROUP_ACCESS_DENIED"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "SETTLEMENT_NOT_FOUND 또는 SETTLEMENT_PARTICIPANT_CANDIDATE_NOT_FOUND"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409",
                    description = "SETTLEMENT_NOT_EDITABLE 또는 SETTLEMENT_PARTICIPANT_ALREADY_EXISTS"
            )
    })
    ApiResponse<SettlementParticipantWebResponse> addSettlementParticipant(
            UUID settlementId,
            AddSettlementParticipantWebRequest request
    );

    @Operation(
            summary = "정산 참여자 목록 조회",
            description = "현재 로그인한 생성자가 정산에 저장된 참여자를 표시 순서대로 조회합니다. "
                    + "회원 참여자는 publicId를 포함하며 내부 사용자 ID와 연락처 식별자는 반환하지 않습니다.",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "정산 참여자 목록 조회 성공",
                    useReturnTypeSchema = true
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "UNAUTHORIZED - 인증 필요"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403",
                    description = "SETTLEMENT_ACCESS_DENIED - 접근 권한 없음"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "SETTLEMENT_NOT_FOUND - 정산 없음"
            )
    })
    ApiResponse<List<SettlementParticipantListItemWebResponse>> getSettlementParticipants(
            UUID settlementId
    );

    @Operation(
            summary = "정산 참여자 공개 ID 후보 검색",
            description = "현재 로그인한 생성자가 DRAFT 정산에 추가할 활성 회원을 publicId prefix로 검색합니다. "
                    + "이미 정산에 포함된 회원은 제외하고 publicId 오름차순으로 최대 20명을 반환합니다. "
                    + "내부 사용자 ID와 저장 파일명은 반환하지 않습니다.",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "정산 참여자 후보 검색 성공",
                    useReturnTypeSchema = true
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "INVALID_ARGUMENT 또는 INVALID_PUBLIC_ID_SEARCH_PREFIX - 검색어 오류"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "UNAUTHORIZED - 인증 필요"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403",
                    description = "SETTLEMENT_ACCESS_DENIED - 접근 권한 없음"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "SETTLEMENT_NOT_FOUND - 정산 없음"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409",
                    description = "SETTLEMENT_NOT_EDITABLE - 초안 상태가 아님"
            )
    })
    ApiResponse<List<SettlementParticipantCandidateWebResponse>> searchSettlementParticipantCandidates(
            UUID settlementId,
            @ParameterObject SearchSettlementParticipantWebRequest request
    );

    @Operation(
            summary = "정산 참여자 공개 ID 후보 아바타 조회",
            description = "현재 로그인한 생성자가 DRAFT 정산의 미추가 활성 회원 후보 아바타를 조회합니다. "
                    + "후보가 아니거나 아바타가 없으면 404를 반환합니다.",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "정산 참여자 후보 아바타 조회 성공",
                    content = @Content(
                            mediaType = "image/*",
                            schema = @Schema(type = "string", format = "binary")
                    )
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "INVALID_PUBLIC_ID - 공개 ID 형식 오류"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "UNAUTHORIZED - 인증 필요"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403",
                    description = "SETTLEMENT_ACCESS_DENIED - 접근 권한 없음"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "SETTLEMENT_NOT_FOUND, SETTLEMENT_PARTICIPANT_CANDIDATE_NOT_FOUND 또는 "
                            + "PROFILE_IMAGE_NOT_FOUND"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409",
                    description = "SETTLEMENT_NOT_EDITABLE - 초안 상태가 아님"
            )
    })
    ResponseEntity<byte[]> getSettlementParticipantCandidateAvatar(
            UUID settlementId,
            String publicId
    );

    @Operation(
            summary = "정산 참여자 연락처 일괄 매칭",
            description = "현재 로그인한 생성자가 DRAFT 정산에 추가할 주소록 연락처를 활성 회원과 일괄 매칭합니다. "
                    + "같은 정규화 전화번호는 첫 항목만 반환하고 기존 참여 여부를 함께 제공합니다. "
                    + "사용자별 분당 10회, 24시간당 연락처 2,000개까지 조회할 수 있습니다. "
                    + "원본 전화번호와 HMAC, 내부 사용자 ID, 아바타 저장 파일명은 저장하거나 응답하지 않습니다.",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "정산 참여자 연락처 일괄 매칭 성공",
                    useReturnTypeSchema = true
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "INVALID_ARGUMENT, INVALID_PHONE_NUMBER 또는 "
                            + "INVALID_SETTLEMENT_CONTACT_MATCH_REQUEST - 입력 오류"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "UNAUTHORIZED - 인증 필요"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403",
                    description = "SETTLEMENT_ACCESS_DENIED - 접근 권한 없음"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "SETTLEMENT_NOT_FOUND - 정산 없음"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409",
                    description = "SETTLEMENT_NOT_EDITABLE - 초안 상태가 아님"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "429",
                    description = "TOO_MANY_REQUEST - 사용자별 연락처 매칭 한도 초과"
            )
    })
    ApiResponse<List<SettlementContactCandidateWebResponse>> matchSettlementContacts(
            UUID settlementId,
            MatchSettlementContactsWebRequest request
    );

    @Operation(
            summary = "정산 그룹 구성원 후보 조회",
            description = "현재 로그인한 생성자가 DRAFT 정산에 추가할 그룹의 활성 구성원을 조회합니다. "
                    + "생성자도 해당 그룹의 활성 구성원이어야 하며 이미 정산에 포함된 회원은 제외합니다. "
                    + "그룹 정보가 연결되지 않은 환경에서는 빈 목록을 반환합니다.",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "정산 그룹 구성원 후보 조회 성공",
                    useReturnTypeSchema = true
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "INVALID_ARGUMENT - 그룹 ID 형식 오류"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "UNAUTHORIZED - 인증 필요"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403",
                    description = "SETTLEMENT_ACCESS_DENIED 또는 SETTLEMENT_GROUP_ACCESS_DENIED"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "SETTLEMENT_NOT_FOUND - 정산 없음"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409",
                    description = "SETTLEMENT_NOT_EDITABLE - 초안 상태가 아님"
            )
    })
    ApiResponse<List<SettlementParticipantCandidateWebResponse>> getSettlementGroupParticipantCandidates(
            UUID settlementId,
            Long groupId
    );

    @Operation(
            summary = "정산 참여자 아바타 조회",
            description = "현재 로그인한 생성자가 회원 참여자의 아바타 이미지를 조회합니다. "
                    + "비회원 연락처이거나 등록된 아바타가 없으면 PROFILE_IMAGE_NOT_FOUND를 반환합니다.",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "정산 참여자 아바타 조회 성공",
                    content = @Content(
                            mediaType = "image/*",
                            schema = @Schema(type = "string", format = "binary")
                    )
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "UNAUTHORIZED - 인증 필요"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403",
                    description = "SETTLEMENT_ACCESS_DENIED - 접근 권한 없음"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "SETTLEMENT_NOT_FOUND, SETTLEMENT_PARTICIPANT_NOT_FOUND 또는 "
                            + "PROFILE_IMAGE_NOT_FOUND"
            )
    })
    ResponseEntity<byte[]> getSettlementParticipantAvatar(
            UUID settlementId,
            UUID participantId
    );

    @Operation(
            summary = "정산 참여자 제거",
            description = "현재 로그인한 생성자가 DRAFT 정산의 참여자를 제거합니다. "
                    + "정산 생성자 자신은 참여자에서 제거할 수 없습니다.",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "정산 참여자 제거 성공",
                    useReturnTypeSchema = true
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "UNAUTHORIZED - 인증 필요"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403",
                    description = "SETTLEMENT_ACCESS_DENIED - 접근 권한 없음"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "SETTLEMENT_NOT_FOUND 또는 SETTLEMENT_PARTICIPANT_NOT_FOUND"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409",
                    description = "SETTLEMENT_NOT_EDITABLE 또는 SETTLEMENT_CREATOR_PARTICIPANT_REQUIRED"
            )
    })
    ApiResponse<Void> removeSettlementParticipant(UUID settlementId, UUID participantId);
}
