package com.amateur.howmuchbackend.infra.account.config;

import com.amateur.howmuchbackend.infra.certificate.config.CertificateProperties;
import com.amateur.howmuchbackend.infra.codef.config.CodefProperties;
import com.amateur.howmuchbackend.infra.messaging.kafka.*;

import io.micrometer.core.instrument.MeterRegistry;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.util.backoff.FixedBackOff;

import tools.jackson.databind.ObjectMapper;

import java.time.*;
import java.util.*;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CertificateReferenceProperties.class)
public class CertificateReferenceConfiguration {
    @Bean
    @ConditionalOnProperty(
            prefix = "howmuch.certificate-reference",
            name = "enabled",
            havingValue = "true")
    InitializingBean referencePrerequisites(
            CertificateReferenceProperties p,
            CertificateProperties certificate,
            KafkaMessagingProperties kafka,
            CodefProperties codef,
            JdbcTemplate jdbc) {
        return () -> {
            if (!certificate.enabled() || !kafka.enabled())
                throw new IllegalArgumentException(
                        "Certificate reference requires existing certificate protection and Kafka");
            if (codef.readTimeout().isNegative()
                    || codef.readTimeout().isZero()
                    || codef.connectTimeout().isNegative()
                    || codef.connectTimeout().isZero()
                    || codef.readTimeout()
                                    .plus(codef.connectTimeout())
                                    .compareTo(Duration.ofSeconds(345))
                            > 0)
                throw new IllegalArgumentException(
                        "Reference provider timeout must fit its six-minute execution window");
            var names =
                    new HashSet<>(
                            List.of(
                                    certificate.commandTopic(),
                                    certificate.resultTopic(),
                                    certificate.commandDltTopic(),
                                    certificate.resultDltTopic()));
            names.addAll(
                    List.of(
                            kafka.notificationDeliveryTopic(),
                            kafka.notificationDeliveryDltTopic(),
                            kafka.recapPublishedTopic(),
                            kafka.recapPublishedDltTopic()));
            if (!names.add(p.commandTopic()) || !names.add(p.commandDltTopic()))
                throw new IllegalArgumentException(
                        "Reference topics must not overlap existing messaging topics");
            for (String table :
                    List.of(
                            "certificate_reference_operations",
                            "certificate_reference_command_outbox",
                            "certificate_reference_command_inbox"))
                if (!Boolean.TRUE.equals(
                        jdbc.queryForObject(
                                "SELECT to_regclass(?) IS NOT NULL", Boolean.class, table)))
                    throw new IllegalArgumentException(
                            "Review and apply certificate-reference SQL before enabling the"
                                    + " feature");
            if (!Boolean.TRUE.equals(
                    jdbc.queryForObject(
                            """
SELECT count(*)=2 FROM pg_attribute WHERE attrelid=to_regclass('connected_institutions')
AND attname IN ('authentication_method','authentication_method_confirmed_at') AND NOT attisdropped
""",
                            Boolean.class)))
                throw new IllegalArgumentException(
                        "Apply institution authentication metadata SQL before enabling reference"
                                + " connections");
        };
    }

    @Bean("certificateReferenceKafkaListenerContainerFactory")
    @ConditionalOnProperty(
            prefix = "howmuch.certificate-reference",
            name = "enabled",
            havingValue = "true")
    ConcurrentKafkaListenerContainerFactory<String, String> referenceListenerFactory(
            KafkaProperties kafka,
            KafkaMessagingProperties transport,
            CertificateReferenceProperties p,
            AcknowledgedKafkaSender sender,
            ObjectMapper json,
            Clock clock,
            @Nullable MeterRegistry meters) {
        var handler =
                new DefaultErrorHandler(
                        new SanitizedKafkaDltRecoverer(
                                sender, json, Map.of(p.commandTopic(), p.commandDltTopic()), clock),
                        new FixedBackOff(
                                transport.processingRetryInterval().toMillis(),
                                FixedBackOff.UNLIMITED_ATTEMPTS));
        handler.addNotRetryableExceptions(InvalidKafkaMessageException.class);
        handler.setAckAfterHandle(true);
        return DurableKafkaListenerFactories.create(kafka, transport, handler, meters);
    }

    @Bean("certificateReferenceExecutionScheduler")
    @ConditionalOnProperty(
            prefix = "howmuch.certificate-reference",
            name = "enabled",
            havingValue = "true")
    ThreadPoolTaskScheduler referenceExecutionScheduler(CertificateReferenceProperties properties) {
        return scheduler("certificate-reference-execution-", properties.workerConcurrency());
    }

    @Bean("certificateReferenceRelayScheduler")
    @ConditionalOnProperty(
            prefix = "howmuch.certificate-reference",
            name = "enabled",
            havingValue = "true")
    ThreadPoolTaskScheduler referenceRelayScheduler() {
        return scheduler("certificate-reference-relay-", 1);
    }

    private ThreadPoolTaskScheduler scheduler(String prefix, int size) {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(size);
        scheduler.setThreadNamePrefix(prefix);
        scheduler.setErrorHandler(
                error ->
                        org.slf4j.LoggerFactory.getLogger(CertificateReferenceConfiguration.class)
                                .warn(
                                        "Certificate reference work deferred; durable state"
                                                + " retained"));
        return scheduler;
    }
}
