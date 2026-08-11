package com.amateur.howmuchbackend.adapter.in.web.user;

import com.amateur.howmuchbackend.adapter.in.web.common.ApiResponse;
import com.amateur.howmuchbackend.adapter.in.web.user.docs.UserDocs;
import com.amateur.howmuchbackend.adapter.in.web.user.dto.response.MyProfileWebResponse;
import com.amateur.howmuchbackend.application.port.in.user.GetMyProfileUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v2/user")
public class UserController implements UserDocs {
    private final GetMyProfileUseCase getMyProfileUseCase;

    @GetMapping("/me")
    public ApiResponse<MyProfileWebResponse> getMyProfile() {
        return ApiResponse.success(MyProfileWebResponse.from(getMyProfileUseCase.getMyProfile()));
    }
}
