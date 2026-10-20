package com.amateur.howmuchbackend.application.port.out.recap;

import com.amateur.howmuchbackend.application.recap.model.RecapPublicationMessage;

public interface RecapEventPublisherPort {
    void publish(RecapPublicationMessage message);
}
