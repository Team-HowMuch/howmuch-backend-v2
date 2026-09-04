package com.amateur.howmuchbackend.application.port.out.notification;

import com.amateur.howmuchbackend.domain.notification.UserNotification;
public interface SaveNotificationPort {
    boolean saveIfAbsent(UserNotification notification);
}
