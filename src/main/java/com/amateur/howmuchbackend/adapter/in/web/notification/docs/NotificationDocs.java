package com.amateur.howmuchbackend.adapter.in.web.notification.docs;

import com.amateur.howmuchbackend.adapter.in.web.common.ApiResponse;
import com.amateur.howmuchbackend.adapter.in.web.notification.dto.request.NotificationListWebRequest;
import com.amateur.howmuchbackend.adapter.in.web.notification.dto.response.*;
import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;

@Tag(name="알림함 API", description="로그인 회원의 정산 요청·리마인드 알림함")
@SecurityRequirement(name="bearerAuth")
public interface NotificationDocs {
    @Operation(summary="내 알림 목록", description="최신순 커서 조회. size 기본20, 최대50. readAt=null은 미읽음이며 조회로 읽음 처리하지 않습니다. 시각은 UTC입니다. 정산 삭제 시 settlementId=null, action.enabled=false입니다.")
    @ApiResponses({@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="200", useReturnTypeSchema=true),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="400", description="INVALID_NOTIFICATION_CURSOR 또는 INVALID_NOTIFICATION_PAGE_SIZE"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="401", description="인증 필요")})
    ApiResponse<NotificationPageWebResponse> get(NotificationListWebRequest request);
    @Operation(summary="내 미읽음 알림 개수")
    ApiResponse<UnreadNotificationCountWebResponse> countUnread();
    @Operation(summary="내 알림 한 건 읽음 처리", description="알림 클릭 시 호출합니다. 반복 호출은 최초 readAt을 유지합니다. 타인 알림과 없는 알림은 같은 404입니다.")
    @ApiResponses({@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="200", useReturnTypeSchema=true),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="404", description="NOTIFICATION_NOT_FOUND"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="401", description="인증 필요")})
    ApiResponse<NotificationReadWebResponse> read(UUID notificationId);
}
