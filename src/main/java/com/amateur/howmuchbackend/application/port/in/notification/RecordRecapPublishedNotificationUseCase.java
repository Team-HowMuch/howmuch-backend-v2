package com.amateur.howmuchbackend.application.port.in.notification;

import com.amateur.howmuchbackend.application.port.in.notification.command.RecapPublishedCommand;

public interface RecordRecapPublishedNotificationUseCase {
    void record(RecapPublishedCommand command);
}
