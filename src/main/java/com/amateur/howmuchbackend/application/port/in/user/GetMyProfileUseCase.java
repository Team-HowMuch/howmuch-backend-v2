package com.amateur.howmuchbackend.application.port.in.user;

import com.amateur.howmuchbackend.application.port.in.user.response.MyProfileResponse;

public interface GetMyProfileUseCase {
    MyProfileResponse getMyProfile();
}
