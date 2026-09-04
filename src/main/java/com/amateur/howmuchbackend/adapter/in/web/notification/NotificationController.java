package com.amateur.howmuchbackend.adapter.in.web.notification;

import com.amateur.howmuchbackend.adapter.in.web.common.ApiResponse;
import com.amateur.howmuchbackend.adapter.in.web.notification.docs.NotificationDocs;
import com.amateur.howmuchbackend.adapter.in.web.notification.dto.request.NotificationListWebRequest;
import com.amateur.howmuchbackend.adapter.in.web.notification.dto.response.*;
import com.amateur.howmuchbackend.application.port.in.notification.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/api/v2/notifications")
@RequiredArgsConstructor
public class NotificationController implements NotificationDocs {
    private final GetMyNotificationsUseCase getUseCase;
    private final CountMyUnreadNotificationsUseCase countUseCase;
    private final ReadMyNotificationUseCase readUseCase;
    @Override @GetMapping
    public ApiResponse<NotificationPageWebResponse> get(@Valid @ModelAttribute NotificationListWebRequest request) {
        return ApiResponse.success(NotificationPageWebResponse.from(getUseCase.get(request.toCommand())));
    }
    @Override @GetMapping("/unread-count")
    public ApiResponse<UnreadNotificationCountWebResponse> countUnread() {
        return ApiResponse.success(UnreadNotificationCountWebResponse.from(countUseCase.countUnread()));
    }
    @Override @PatchMapping("/{notificationId}/read")
    public ApiResponse<NotificationReadWebResponse> read(@PathVariable UUID notificationId) {
        return ApiResponse.success(NotificationReadWebResponse.from(readUseCase.read(notificationId)));
    }
}
