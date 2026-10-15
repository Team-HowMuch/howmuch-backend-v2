package com.amateur.howmuchbackend.infra.messaging.kafka;

public final class KafkaPublicationException extends RuntimeException {
    public KafkaPublicationException() {
        super("Kafka publication was not acknowledged within its transport deadline.");
    }
}
