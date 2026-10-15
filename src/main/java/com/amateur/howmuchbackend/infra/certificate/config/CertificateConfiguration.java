package com.amateur.howmuchbackend.infra.certificate.config;

import com.amateur.howmuchbackend.infra.messaging.kafka.*;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.context.annotation.*;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.util.backoff.FixedBackOff;

import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.util.Map;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CertificateProperties.class)
public class CertificateConfiguration {
    @Bean
    @ConditionalOnProperty(prefix = "howmuch.certificate", name = "enabled", havingValue = "true")
    InitializingBean certificateTransportRequired(
            KafkaMessagingProperties messaging,
            com.amateur.howmuchbackend.infra.codef.config.CodefProperties codef) {
        return () -> {
            if (!messaging.enabled())
                throw new IllegalArgumentException(
                        "Certificate registration requires enabled Kafka transport");
            if (!codef.hasClientCredentials() || !codef.hasPublicKey()) {
                throw new IllegalArgumentException(
                        "Certificate registration requires configured CODEF credentials and public"
                                + " key");
            }
            if (codef.readTimeout()
                            .plus(codef.connectTimeout())
                            .compareTo(java.time.Duration.ofMinutes(9))
                    >= 0) {
                throw new IllegalArgumentException(
                        "Certificate provider timeout must fit the ten-minute execution lease");
            }
        };
    }

    @Bean("certificateKafkaListenerContainerFactory")
    @ConditionalOnProperty(prefix = "howmuch.certificate", name = "enabled", havingValue = "true")
    ConcurrentKafkaListenerContainerFactory<String, String>
            certificateKafkaListenerContainerFactory(
                    KafkaProperties kafka,
                    KafkaMessagingProperties messaging,
                    CertificateProperties p,
                    AcknowledgedKafkaSender sender,
                    ObjectMapper json,
                    Clock clock) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, String>();
        var consumer = kafka.buildConsumerProperties();
        consumer.put("enable.auto.commit", false);
        consumer.put("allow.auto.create.topics", false);
        factory.setConsumerFactory(new DefaultKafkaConsumerFactory<>(consumer));
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        factory.getContainerProperties().setSyncCommits(true);
        factory.setConcurrency(messaging.concurrency());
        var handler =
                new DefaultErrorHandler(
                        new SanitizedKafkaDltRecoverer(
                                sender,
                                json,
                                Map.of(
                                        p.commandTopic(),
                                        p.commandDltTopic(),
                                        p.resultTopic(),
                                        p.resultDltTopic()),
                                clock),
                        new FixedBackOff(
                                messaging.processingRetryInterval().toMillis(),
                                FixedBackOff.UNLIMITED_ATTEMPTS));
        handler.addNotRetryableExceptions(InvalidKafkaMessageException.class);
        handler.setAckAfterHandle(true);
        factory.setCommonErrorHandler(handler);
        return factory;
    }

    @Bean("certificateExecutionScheduler")
    @ConditionalOnProperty(prefix = "howmuch.certificate", name = "enabled", havingValue = "true")
    ThreadPoolTaskScheduler certificateExecutionScheduler() {
        return scheduler("certificate-execution-", 1);
    }

    @Bean("certificateRelayScheduler")
    @ConditionalOnProperty(prefix = "howmuch.certificate", name = "enabled", havingValue = "true")
    ThreadPoolTaskScheduler certificateRelayScheduler() {
        return scheduler("certificate-relay-", 2);
    }

    private ThreadPoolTaskScheduler scheduler(String prefix, int size) {
        var s = new ThreadPoolTaskScheduler();
        s.setPoolSize(size);
        s.setThreadNamePrefix(prefix);
        s.setErrorHandler(
                error ->
                        org.slf4j.LoggerFactory.getLogger(CertificateConfiguration.class)
                                .warn(
                                        "Certificate scheduled operation failed; durable state"
                                                + " retained"));
        return s;
    }
}
