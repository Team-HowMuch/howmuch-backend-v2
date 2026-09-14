package com.amateur.howmuchbackend.adapter.in.web.user;

import com.amateur.howmuchbackend.adapter.in.web.common.ApiResponse;
import com.amateur.howmuchbackend.adapter.in.web.common.ImageWebResponse;
import com.amateur.howmuchbackend.adapter.in.web.user.docs.UserDocs;
import com.amateur.howmuchbackend.adapter.in.web.user.dto.request.RegisterPushTargetWebRequest;
import com.amateur.howmuchbackend.adapter.in.web.user.dto.request.DeleteMyAccountWebRequest;
import com.amateur.howmuchbackend.adapter.in.web.user.dto.request.UpdateMyProfileRequest;
import com.amateur.howmuchbackend.adapter.in.web.user.dto.response.MyProfileWebResponse;
import com.amateur.howmuchbackend.application.port.in.user.GetMyProfileImageUseCase;
import com.amateur.howmuchbackend.application.port.in.user.GetMyProfileUseCase;
import com.amateur.howmuchbackend.application.port.in.user.ManageMyPushTargetUseCase;
import com.amateur.howmuchbackend.application.port.in.user.DeleteMyAccountUseCase;
import com.amateur.howmuchbackend.application.port.in.user.UpdateMyProfileUseCase;
import com.amateur.howmuchbackend.application.user.model.ProfileImage;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v2/user")
public class UserController implements UserDocs {
    private final GetMyProfileUseCase getMyProfileUseCase;
    private final GetMyProfileImageUseCase getMyProfileImageUseCase;
    private final UpdateMyProfileUseCase updateMyProfileUseCase;
    private final ManageMyPushTargetUseCase manageMyPushTargetUseCase;
    private final DeleteMyAccountUseCase deleteMyAccountUseCase;

    @GetMapping("/me")
    public ApiResponse<MyProfileWebResponse> getMyProfile() {
        return ApiResponse.success(MyProfileWebResponse.from(getMyProfileUseCase.getMyProfile()));
    }

    @GetMapping("/me/avatar")
    public ResponseEntity<byte[]> getMyProfileImage() {
        ProfileImage image = getMyProfileImageUseCase.getMyProfileImage();
        return ImageWebResponse.ok(image.contentType(), image.content());
    }

    @PatchMapping(value = "/me/profile", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<MyProfileWebResponse> updateMyProfile(
            @ModelAttribute UpdateMyProfileRequest request
    ) {
        return ApiResponse.success(MyProfileWebResponse.from(updateMyProfileUseCase.update(request.toCommand())));
    }

    @PutMapping("/me/push-target")
    public ApiResponse<Void> registerPushTarget(
            @Valid @RequestBody RegisterPushTargetWebRequest request
    ) {
        manageMyPushTargetUseCase.register(request.firebaseInstallationId());
        return ApiResponse.success();
    }

    @DeleteMapping("/me/push-target")
    public ApiResponse<Void> clearPushTarget() {
        manageMyPushTargetUseCase.clear();
        return ApiResponse.success();
    }

    @DeleteMapping("/me")
    public ApiResponse<Void> deleteMyAccount(
            @Valid @RequestBody DeleteMyAccountWebRequest request
    ) {
        deleteMyAccountUseCase.delete(request.toCommand());
        return ApiResponse.success();
    }
}
