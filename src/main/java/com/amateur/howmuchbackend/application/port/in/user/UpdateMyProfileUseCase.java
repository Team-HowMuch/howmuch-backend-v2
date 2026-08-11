package com.amateur.howmuchbackend.application.port.in.user;

import com.amateur.howmuchbackend.application.port.in.user.request.UpdateMyProfileRequest;
import com.amateur.howmuchbackend.application.port.in.user.response.MyProfileResponse;

public interface UpdateMyProfileUseCase {
    MyProfileResponse update(UpdateMyProfileRequest request);
}
