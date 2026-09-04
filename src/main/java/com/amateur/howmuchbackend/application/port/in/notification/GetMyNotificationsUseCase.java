package com.amateur.howmuchbackend.application.port.in.notification;

import com.amateur.howmuchbackend.application.port.in.notification.command.GetMyNotificationsCommand;
import com.amateur.howmuchbackend.application.port.in.notification.result.*;

public interface GetMyNotificationsUseCase { NotificationPageResult get(GetMyNotificationsCommand command); }
