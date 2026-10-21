package com.amateur.howmuchbackend.adapter.in.web.settlement.docs;

import com.amateur.howmuchbackend.adapter.in.web.common.ApiResponse;
import com.amateur.howmuchbackend.adapter.in.web.settlement.dto.response.SettlementLinkEntryWebResponse;
import com.amateur.howmuchbackend.adapter.in.web.settlement.dto.response.SettlementLinkParticipantSelectionWebResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;

import java.util.UUID;

@Tag(name = "공개 정산 링크 API", description = "공통 링크 진입과 참여자 선택 API")
public interface SettlementLinkEntryDocs {
    @Operation(
            summary = "공통 정산 링크 조회",
            description = "인증 없이 참여자 이름 목록을 조회할 수 있습니다. 유효한 Bearer 인증이 있고 "
                    + "회원 참여자와 연결되면 APP_DIRECT와 단기 참여 문맥을 반환합니다. "
                    + "participantCount는 요청자를 포함한 전체 참여자 수입니다. 요청자 여부와 참여자별 "
                    + "금액을 포함합니다. 입금 대기 중 더치페이의 0원 참여자는 amount=0이며 송금 안내 대상이 아닙니다. "
                    + "selectedParticipant와 participantCandidates의 avatarUrl은 활성 회원의 프로필 이미지 상대 URL이며 "
                    + "비회원·탈퇴 회원·사진 미등록은 null입니다. 이미지도 유효한 링크로 로그인 없이 조회합니다. "
                    + "품목 정산 또는 중단 후 재배분 중인 참여자의 미확정 amount는 null입니다. "
                    + "requestedAt은 공유 링크 생성 시각이며 링크 생성 시각이 없으면 정산 생성 시각입니다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "공통 링크 조회 또는 앱 회원 자동 매칭 성공",
                    useReturnTypeSchema = true
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "SETTLEMENT_LINK_NOT_FOUND - 형식 오류·만료·비활성·사용 불가 링크"
            )
    })
    ApiResponse<SettlementLinkEntryWebResponse> resolve(
            @Parameter(description = "공통 URL의 43자 불투명 token") String token
    );

    @Operation(
            summary = "공통 링크 참여자 선택",
            description = "브라우저 사용자가 본인이라고 판단한 참여자를 신뢰 기반으로 선택하고 "
                    + "30분 단기 참여 문맥을 발급합니다. participant.avatarUrl은 링크 조회와 동일한 nullable 이미지 URL입니다. "
                    + "오선택·사칭 방지는 MVP 범위에 포함하지 않습니다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "참여자 선택과 단기 참여 문맥 발급 성공",
                    useReturnTypeSchema = true
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "SETTLEMENT_LINK_NOT_FOUND 또는 SETTLEMENT_PARTICIPANT_NOT_FOUND"
            )
    })
    ApiResponse<SettlementLinkParticipantSelectionWebResponse> selectParticipant(
            @Parameter(description = "공통 URL의 43자 불투명 token") String token,
            @Parameter(description = "사용자가 선택한 정산 참여자 ID") UUID participantId
    );

    @Operation(summary = "공통 정산 링크 참여자 프로필 이미지 조회",
            description = "로그인 없이 유효한 링크에 속한 참여자의 현재 프로필 이미지를 조회합니다. "
                    + "링크 만료·비활성·사용 불가 정산, 다른 정산의 참여자, 비회원·탈퇴 회원·사진 없음은 404입니다. "
                    + "이미지 버전 v는 캐시 구분용이며 접근 권한으로 사용하지 않습니다. 응답은 Cache-Control: no-store입니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "프로필 이미지 바이트", useReturnTypeSchema = true),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                    description = "SETTLEMENT_LINK_NOT_FOUND, SETTLEMENT_PARTICIPANT_NOT_FOUND 또는 PROFILE_IMAGE_NOT_FOUND")
    })
    ResponseEntity<byte[]> getParticipantAvatar(
            @Parameter(description = "공통 URL의 43자 불투명 token") String token,
            @Parameter(description = "해당 정산의 참여자 ID") UUID participantId
    );
}
