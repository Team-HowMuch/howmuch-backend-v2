package com.amateur.howmuchbackend.infra.messaging.kafka;

import io.micrometer.core.instrument.MeterRegistry;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.MicrometerConsumerListener;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;

public final class DurableKafkaListenerFactories {
    private DurableKafkaListenerFactories() {}

    public static ConcurrentKafkaListenerContainerFactory<String, String> create(
            KafkaProperties kafka,
            KafkaMessagingProperties properties,
            DefaultErrorHandler handler,
            @Nullable MeterRegistry meters) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, String>();
        var consumer = kafka.buildConsumerProperties();
        consumer.put("enable.auto.commit", false);
        consumer.put("allow.auto.create.topics", false);
        var clients = new DefaultKafkaConsumerFactory<String, String>(consumer);
        if (meters != null) clients.addListener(new MicrometerConsumerListener<>(meters));
        factory.setConsumerFactory(clients);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        factory.getContainerProperties().setSyncCommits(true);
        factory.getContainerProperties().setObservationEnabled(true);
        factory.setConcurrency(properties.concurrency());
        factory.setCommonErrorHandler(handler);
        return factory;
    }
}
