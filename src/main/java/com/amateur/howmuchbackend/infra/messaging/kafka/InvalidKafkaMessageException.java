package com.amateur.howmuchbackend.infra.messaging.kafka;

/** Contains only a fixed classification, never the rejected payload or parser cause. */
public final class InvalidKafkaMessageException extends RuntimeException {
    public InvalidKafkaMessageException() {
        super("Kafka message violates its wire contract.");
    }
}
