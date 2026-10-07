package com.amateur.howmuchbackend.infra.messaging.kafka;

import com.amateur.howmuchbackend.adapter.in.kafka.notification.NotificationDeliveryKafkaListener;
import com.amateur.howmuchbackend.adapter.in.kafka.recap.RecapPublishedKafkaListener;
import com.amateur.howmuchbackend.adapter.out.kafka.notification.NotificationDeliveryKafkaPublisher;
import com.amateur.howmuchbackend.application.port.in.notification.DeliverNotificationUseCase;
import com.amateur.howmuchbackend.application.port.in.notification.RecordRecapPublishedNotificationUseCase;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class KafkaMessagingConfigurationTest {
    @TempDir Path configDirectory;
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfig.class, KafkaMessagingConfig.class,
                    NotificationDeliveryKafkaPublisher.class, NotificationDeliveryKafkaListener.class,
                    RecapPublishedKafkaListener.class)
            .withBean(KafkaTemplate.class, () -> mock(KafkaTemplate.class))
            .withBean(ObjectMapper.class, () -> JsonMapper.builder().build())
            .withBean(Clock.class, Clock::systemUTC)
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
            .withBean(DeliverNotificationUseCase.class, () -> mock(DeliverNotificationUseCase.class))
            .withBean(RecordRecapPublishedNotificationUseCase.class,
                    () -> mock(RecordRecapPublishedNotificationUseCase.class));

    @Test
    void disabledDefaultRegistersNoKafkaTransportAndDoesNotStartConsumers() {
        context.run(app -> {
            assertThat(app).doesNotHaveBean(AcknowledgedKafkaSender.class)
                    .doesNotHaveBean(NotificationDeliveryKafkaPublisher.class)
                    .doesNotHaveBean(NotificationDeliveryKafkaListener.class)
                    .doesNotHaveBean(RecapPublishedKafkaListener.class);
            var properties = app.getBean(KafkaMessagingProperties.class);
            assertThat(properties.enabled()).isFalse();
            assertThat(properties.notificationConsumerEnabled()).isFalse();
            assertThat(properties.recapConsumerEnabled()).isFalse();
            assertThat(properties.publishTimeout()).isEqualTo(Duration.ofSeconds(5));
        });
    }

    @Test
    void directModeWithKafkaEnabledStillDoesNotRegisterNotificationConsumer() {
        context.withPropertyValues("howmuch.messaging.kafka.enabled=true", "howmuch.notification.delivery-mode=DIRECT")
                .run(app -> {
                    assertThat(app).hasNotFailed().hasSingleBean(AcknowledgedKafkaSender.class)
                            .doesNotHaveBean(NotificationDeliveryKafkaListener.class);
                });
    }

    @Test
    void kafkaModeUsesSynchronousRecordOffsetsAndSeparateTopicGroups() {
        context.withPropertyValues("howmuch.messaging.kafka.enabled=true", "howmuch.notification.delivery-mode=KAFKA")
                .run(app -> {
                    assertThat(app).hasNotFailed().hasSingleBean(NotificationDeliveryKafkaListener.class);
                    var factory = app.getBean("durableKafkaListenerContainerFactory", ConcurrentKafkaListenerContainerFactory.class);
                    assertThat(factory.getContainerProperties().getAckMode()).isEqualTo(ContainerProperties.AckMode.RECORD);
                    assertThat(factory.getContainerProperties().isSyncCommits()).isTrue();
                    assertThat(factory.getContainerProperties().isObservationEnabled()).isTrue();
                    var props = app.getBean(KafkaMessagingProperties.class);
                    assertThat(props.notificationDeliveryGroup()).isNotEqualTo(props.recapPublishedGroup());
                });
    }

    @Test
    void enabledKafkaWithoutExplicitBootstrapServerFailsStartup() {
        context.withPropertyValues("howmuch.messaging.kafka.enabled=true", "spring.kafka.bootstrap-servers=")
                .run(app -> assertThat(app).hasFailed());
    }

    @Test
    void invalidTransportBoundsAndCrossTopicCollisionsFailStartup() {
        for (String setting : new String[]{"howmuch.messaging.kafka.publish-timeout=0s",
                "howmuch.messaging.kafka.concurrency=33",
                "howmuch.messaging.kafka.recap-published-dlt-topic=notification.delivery.v1"}) {
            context.withPropertyValues(setting).run(app -> assertThat(app).hasFailed());
        }
    }

    @Test
    void invalidOrContradictoryDeliveryModesFailStartup() {
        for (String setting : new String[]{"howmuch.notification.delivery-mode=KAFA",
                "howmuch.notification.delivery-mode=kafka", "howmuch.notification.delivery-mode=KAFKA",
                "howmuch.messaging.kafka.notification-consumer-enabled=true",
                "howmuch.messaging.kafka.recap-consumer-enabled=true"}) {
            context.withPropertyValues(setting).run(app -> assertThat(app).hasFailed());
        }
        context.withPropertyValues("howmuch.messaging.kafka.enabled=true",
                        "howmuch.messaging.kafka.notification-consumer-enabled=true", "howmuch.notification.delivery-mode=DIRECT")
                .run(app -> assertThat(app).hasFailed());
        context.withPropertyValues("howmuch.messaging.kafka.enabled=true",
                        "howmuch.messaging.kafka.recap-consumer-enabled=true", "howmuch.notification.delivery-mode=DIRECT")
                .run(app -> assertThat(app).hasNotFailed());
    }

    @Test
    void externalMtlsReferenceLoadsPasswordsFromMountedConfigTree() throws Exception {
        var secrets = Files.createDirectory(configDirectory.resolve("secrets"));
        Files.writeString(secrets.resolve("kafka.keystore.password"), "test-keystore-password");
        Files.writeString(secrets.resolve("kafka.key.password"), "test-key-password");
        Files.writeString(secrets.resolve("kafka.truststore.password"), "test-truststore-password");
        var source = Files.readString(Path.of("ops/kafka/client-mtls.reference.yml"));
        var externalConfig = configDirectory.resolve("kafka-client.yml");
        Files.writeString(externalConfig, source.replace("configtree:/run/secrets/kafka-client/",
                "configtree:" + secrets.toAbsolutePath() + "/"));

        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(PropertiesConfig.class)
                .withPropertyValues("spring.config.name=mtls-reference-test",
                        "spring.config.additional-location=" + externalConfig.toUri())
                .run(app -> {
                    assertThat(app).hasNotFailed();
                    var kafka = app.getBean(KafkaProperties.class);
                    assertThat(kafka.buildProducerProperties())
                            .containsEntry("security.protocol", "SSL")
                            .containsEntry("ssl.keystore.type", "PKCS12")
                            .containsEntry("ssl.keystore.password", "test-keystore-password")
                            .containsEntry("ssl.key.password", "test-key-password")
                            .containsEntry("ssl.truststore.password", "test-truststore-password")
                            .containsEntry("ssl.keystore.location", "/run/secrets/kafka-client/client.keystore.p12");
                    assertThat(kafka.buildConsumerProperties())
                            .containsEntry("security.protocol", "SSL")
                            .containsEntry("ssl.truststore.location", "/run/secrets/kafka-client/cluster.truststore.p12")
                            .containsEntry("ssl.endpoint.identification.algorithm", "https");
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(KafkaProperties.class)
    static class PropertiesConfig {}
}
