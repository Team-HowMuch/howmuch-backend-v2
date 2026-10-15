package com.amateur.howmuchbackend.infra.messaging.kafka;

/** Keeps consumer infrastructure logs free from database/provider exception bodies. */
public final class KafkaProcessingException extends RuntimeException {
    public KafkaProcessingException() {
        super("Local Kafka message processing did not complete.");
    }
}
