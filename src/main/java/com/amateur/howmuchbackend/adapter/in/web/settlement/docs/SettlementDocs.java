package com.amateur.howmuchbackend.adapter.in.web.settlement.docs;

import com.amateur.howmuchbackend.adapter.in.web.common.ApiResponse;
import com.amateur.howmuchbackend.adapter.in.web.settlement.dto.request.AddSettlementParticipantWebRequest;
import com.amateur.howmuchbackend.adapter.in.web.settlement.dto.request.RenameSettlementParticipantWebRequest;
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
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.http.ResponseEntity;
import org.springdoc.core.annotations.ParameterObject;

import java.util.List;
import java.util.UUID;

@Tag(name = "정산 API", description = "정산 초안·상태·참여자 관리 API")
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
    ApiResponse<SettlementWebResponse> createSettlement(@Valid CreateSettlementWebRequest request);

    @Operation(
            summary = "정산 상세 조회",
            description = "정산 생성자는 전체 납부 현황을, 연결 참여자는 자신의 납부 행을 조회합니다. "
                    + "endedAt은 완료·중단 상태의 공통 종료 시각이며 COMPLETED는 완료 시각, "
                    + "SUSPENDED는 현재 중단 시각입니다. 그 외 상태는 null이며 UTC 기준 offset 없는 값입니다. "
                    + "참여자의 avatarUrl은 이미지 파일 버전 v 쿼리를 포함하며 사진이 없거나 비회원이면 null입니다. "
                    + "실제 입금 확인액, 미납액, 진행률과 참여자별 납부 상태를 반환하며 "
                    + "참여자별 submitted는 품목 선택 제출 기록 여부를 나타냅니다. "
                    + "paymentInfo.deliveryFee는 전체 순 배달비, 참여자의 deliveryFeeShare는 품목 비율 배달비 몫입니다. "
                    + "전체 할인·배달비가 있으면 배분 확정 전 expectedAmount는 null이며 NOT_READY입니다. "
                    + "내부 사용자 ID, 연락처와 계좌번호는 노출하지 않습니다.",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "정산 상세 조회 성공",
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
    ApiResponse<SettlementDetailWebResponse> getSettlement(UUID settlementId);

    @Operation(
            summary = "정산 목록 조회",
            description = "현재 로그인 사용자가 생성했거나 등록 참여자로 연결된 정산을 탭·상태별로 조회합니다. "
                    + "groups.groupImageUrl은 이미지 파일 버전 v 쿼리를 포함하며 이미지가 없으면 null입니다. "
                    + "응답의 status는 실제 정산 상태이며, 화면에서 진행중·완료 그룹으로 매핑할 수 있습니다. "
                    + "금액과 인원은 문장이 아닌 숫자 필드로 반환합니다. "
                    + "endedAt은 종료 상태 공통 시각입니다. COMPLETED에서는 완료 시각, SUSPENDED에서는 현재 중단 시각이며 "
                    + "그 외 상태는 null입니다. UTC 기준 offset 없는 값입니다. "
                    + "completedAt은 기존 호환 필드로 완료 정산에만 값을 제공합니다. "
                    + "yearMonth(yyyy-MM)는 Asia/Seoul 기준 완료 월이며 COMPLETED 정산에만 적용되고 생략하면 전체 기간입니다. "
                    + "기존 상태·그룹 조건과 함께 적용하므로 월별 완료 정산은 status=COMPLETED 또는 ALL로 조회하세요. "
                    + "status 생략 시 IN_PROGRESS가 유지되어 yearMonth와 함께 지정하면 빈 목록입니다.",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "정산 목록 조회 성공",
                    useReturnTypeSchema = true
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "INVALID_ARGUMENT - tab, status, groupId 또는 yearMonth 값이 올바르지 않음"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "UNAUTHORIZED - 인증 필요"
            )
    })
    ApiResponse<List<SettlementListItemWebResponse>> getSettlements(
            @Valid @ParameterObject SettlementListWebRequest request
    );

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
            @Valid UpdateSettlementDraftWebRequest request
    );

    @Operation(
            summary = "정산 수동 중단",
            description = "현재 로그인한 생성자가 REQUESTED 또는 AWAITING_DEPOSIT 상태의 정산을 SUSPENDED 상태로 중단합니다. "
                    + "다른 참여자의 Share 또는 제출 기록이 있으면 CODEF 거래내역을 다시 조회해 입금 여부를 확인합니다. "
                    + "부분·완납 입금이 확인되거나 미귀속 양수 입금이 있으면 중단할 수 없습니다. "
                    + "중단 전에 은행 앱에서 시작한 송금은 취소되지 않을 수 있습니다. "
                    + "정산이 없으면 SETTLEMENT_NOT_FOUND, 다른 생성자의 정산이면 SETTLEMENT_ACCESS_DENIED, "
                    + "허용되지 않은 상태면 INVALID_SETTLEMENT_STATUS_TRANSITION, "
                    + "입금이 확인되었거나 귀속이 불명확하면 SETTLEMENT_NOT_SUSPENDABLE을 반환합니다. "
                    + "재조회 중 상태·계좌·참여 Share 또는 제출 기록이 변경되면 CONCURRENT_UPDATE를 반환합니다. "
                    + "CODEF 조회 실패와 추가 인증은 해당 조회 오류를 반환합니다.",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "정산 수동 중단 성공",
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
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409",
                    description = "INVALID_SETTLEMENT_STATUS_TRANSITION - 허용되지 않은 상태 변경, "
                            + "SETTLEMENT_NOT_SUSPENDABLE - 입금 확인 또는 입금 귀속 불명확, "
                            + "CONCURRENT_UPDATE - 재조회 중 상태·계좌·참여 Share 또는 제출 기록 변경"
            )
    })
    ApiResponse<SettlementWebResponse> suspendSettlement(UUID settlementId);

    @Operation(
            summary = "정산 재개",
            description = "현재 로그인한 생성자가 SUSPENDED 정산을 재개합니다. 품목 정산은 REQUESTED가 됩니다. 더치페이는 입금 기록이 없고 지출·전원 배분 합계가 정산 총액과 같을 때 Share를 다시 확정해 AWAITING_DEPOSIT으로 전환합니다. "
                    + "재개 시 참여자 제출 완료 표시는 초기화하고 기존 품목 선택은 유지합니다. "
                    + "정산이 없으면 SETTLEMENT_NOT_FOUND, 다른 생성자의 정산이면 SETTLEMENT_ACCESS_DENIED, "
                    + "허용되지 않은 상태면 INVALID_SETTLEMENT_STATUS_TRANSITION을 반환합니다.",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "정산 재개 성공",
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
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409",
                    description = "INVALID_SETTLEMENT_STATUS_TRANSITION, SETTLEMENT_REVISION_PAYMENT_EXISTS, SETTLEMENT_EXPENSE_TOTAL_MISMATCH, SETTLEMENT_SHARE_TOTAL_MISMATCH, SETTLEMENT_SHARE_AMOUNT_REQUIRED, SETTLEMENT_DUTCH_PAY_ALLOCATION_INCOMPLETE"
            )
    })
    ApiResponse<SettlementWebResponse> resumeSettlement(UUID settlementId);

    @Operation(
            summary = "정산 삭제",
            description = "생성자가 정산을 전체 사용자 조회에서 숨깁니다. "
                    + "DRAFT·REQUESTED는 Share가 없을 때, SUSPENDED·COMPLETED는 Share와 관계없이 삭제할 수 있습니다. "
                    + "AWAITING_DEPOSIT은 삭제할 수 없으며 금융 이력은 보존됩니다.",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "정산 삭제 성공",
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
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409",
                    description = "SETTLEMENT_NOT_DELETABLE - AWAITING_DEPOSIT 또는 Share가 있는 DRAFT·REQUESTED 정산"
            )
    })
    ApiResponse<Void> deleteSettlement(UUID settlementId);

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
            @Valid AddSettlementParticipantWebRequest request
    );

    @Operation(
            summary = "정산 참여자 목록 조회",
            description = "현재 로그인한 생성자가 정산에 저장된 참여자를 표시 순서대로 조회합니다. "
                    + "회원 참여자의 avatarUrl은 이미지 파일 버전 v 쿼리를 포함합니다. "
                    + "내부 사용자 ID와 연락처 식별자는 반환하지 않습니다.",
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
                    + "avatarUrl은 이미지 파일 버전 v 쿼리를 포함하며 사진이 없으면 null입니다. "
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
            @Valid @ParameterObject SearchSettlementParticipantWebRequest request
    );

    @Operation(
            summary = "정산 참여자 공개 ID 후보 아바타 조회",
            description = "현재 로그인한 생성자가 DRAFT 정산의 미추가 활성 회원 후보 아바타를 조회합니다. "
                    + "후보가 아니거나 아바타가 없으면 404를 반환합니다. "
                    + "응답은 private 캐시에서 1일간 재사용할 수 있습니다.",
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
                    + "avatarUrl은 이미지 파일 버전 v 쿼리를 포함하며 사진이 없으면 null입니다. "
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
            @Valid MatchSettlementContactsWebRequest request
    );

    @Operation(
            summary = "정산 그룹 구성원 후보 조회",
            description = "현재 로그인한 생성자가 DRAFT 정산에 추가할 그룹의 활성 구성원을 조회합니다. "
                    + "생성자도 해당 그룹의 활성 구성원이어야 하며 이미 정산에 포함된 회원은 제외합니다. "
                    + "avatarUrl은 이미지 파일 버전 v 쿼리를 포함하며 사진이 없으면 null입니다. "
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
                    description = "INVALID_ARGUMENT 또는 INVALID_SETTLEMENT_GROUP_ID - 그룹 ID 오류"
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
            @Positive Long groupId
    );

    @Operation(
            summary = "정산 참여자 아바타 조회",
            description = "현재 로그인한 생성자가 회원 참여자의 아바타 이미지를 조회합니다. "
                    + "비회원 연락처이거나 등록된 아바타가 없으면 PROFILE_IMAGE_NOT_FOUND를 반환합니다. "
                    + "응답은 private 캐시에서 1일간 재사용할 수 있습니다.",
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

    @Operation(
            summary = "정산 참여자 이름 수정",
            description = "현재 로그인한 생성자가 DRAFT·SUSPENDED 정산의 참여자를 이 정산에서 부를 이름으로 바꿉니다. "
                    + "SUSPENDED에서는 확정 입금이 없을 때만 수정할 수 있으며 수정 후에도 중단 상태를 유지합니다. "
                    + "재개 API를 호출해야 정산이 다시 진행됩니다. "
                    + "연락처에 없어 자동 생성된 참여자를 구분하기 위한 기능이며, "
                    + "가입 회원 참여자에게 적용해도 해당 회원의 계정 이름은 바뀌지 않습니다. "
                    + "정산 생성자 자신의 이름은 바꿀 수 없습니다.",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "정산 참여자 이름 수정 성공",
                    useReturnTypeSchema = true
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "INVALID_SETTLEMENT_PARTICIPANT_DISPLAY_NAME - 표시 이름 형식 오류"
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
                    description = "SETTLEMENT_NOT_EDITABLE, SETTLEMENT_CREATOR_PARTICIPANT_REQUIRED "
                            + "또는 SETTLEMENT_REVISION_PAYMENT_EXISTS"
            )
    })
    ApiResponse<SettlementParticipantWebResponse> renameSettlementParticipant(
            UUID settlementId,
            UUID participantId,
            @Valid RenameSettlementParticipantWebRequest request
    );
}
