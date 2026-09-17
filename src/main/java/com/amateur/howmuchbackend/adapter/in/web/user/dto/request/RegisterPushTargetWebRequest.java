package com.amateur.howmuchbackend.adapter.in.web.user.dto.request;

import com.amateur.howmuchbackend.domain.user.User;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import io.swagger.v3.oas.annotations.media.Schema;

public record RegisterPushTargetWebRequest(
        @Schema(description = "Android FCM 또는 iOS APNs 네이티브 푸시 토큰")
        @NotBlank
        @Size(max = User.MAX_FCM_TARGET_LENGTH)
        String firebaseInstallationId
) {
}
