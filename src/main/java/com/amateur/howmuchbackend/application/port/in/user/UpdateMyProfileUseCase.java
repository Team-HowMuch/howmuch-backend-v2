package com.amateur.howmuchbackend.application.port.in.user;

import com.amateur.howmuchbackend.application.port.in.user.command.UpdateMyProfileCommand;
import com.amateur.howmuchbackend.application.port.in.user.result.MyProfileResult;

public interface UpdateMyProfileUseCase {
    MyProfileResult update(UpdateMyProfileCommand command);
}
