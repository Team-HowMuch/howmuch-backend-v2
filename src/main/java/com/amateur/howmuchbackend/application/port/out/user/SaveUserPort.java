package com.amateur.howmuchbackend.application.port.out.user;

import com.amateur.howmuchbackend.domain.user.User;

public interface SaveUserPort {
    User save(User user);

    User update(User user);
}
