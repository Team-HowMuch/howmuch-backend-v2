package com.amateur.howmuchbackend.adapter.in.web.user;

import com.amateur.howmuchbackend.adapter.in.web.common.ApiResponse;
import com.amateur.howmuchbackend.adapter.in.web.user.docs.UserDocs;
import com.amateur.howmuchbackend.adapter.in.web.user.dto.response.MyProfileWebResponse;
import com.amateur.howmuchbackend.application.port.in.user.GetMyProfileImageUseCase;
import com.amateur.howmuchbackend.application.port.in.user.GetMyProfileUseCase;
import com.amateur.howmuchbackend.application.user.model.ProfileImage;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v2/user")
public class UserController implements UserDocs {
    private final GetMyProfileUseCase getMyProfileUseCase;
    private final GetMyProfileImageUseCase getMyProfileImageUseCase;

    @GetMapping("/me")
    public ApiResponse<MyProfileWebResponse> getMyProfile() {
        return ApiResponse.success(MyProfileWebResponse.from(getMyProfileUseCase.getMyProfile()));
    }

    @GetMapping("/me/avatar")
    public ResponseEntity<byte[]> getMyProfileImage() {
        ProfileImage image = getMyProfileImageUseCase.getMyProfileImage();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(image.contentType()))
                .body(image.content());
    }
}
