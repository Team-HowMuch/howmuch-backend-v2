package com.amateur.howmuchbackend.application.port.in.group;

import com.amateur.howmuchbackend.application.user.model.ProfileImage;

public interface GetGroupMemberAvatarUseCase {
    ProfileImage getMemberAvatar(Long groupId, Long memberId);
}
