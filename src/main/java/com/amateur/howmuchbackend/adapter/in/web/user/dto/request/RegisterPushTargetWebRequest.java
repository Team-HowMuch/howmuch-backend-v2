package com.amateur.howmuchbackend.adapter.in.web.user.dto.request;

import com.amateur.howmuchbackend.domain.user.User;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterPushTargetWebRequest(
        @NotBlank
        @Size(max = User.MAX_FCM_TARGET_LENGTH)
        String firebaseInstallationId
) {
}
