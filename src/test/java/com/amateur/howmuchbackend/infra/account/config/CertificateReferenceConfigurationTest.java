package com.amateur.howmuchbackend.infra.account.config;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.amateur.howmuchbackend.infra.certificate.config.CertificateProperties;
import com.amateur.howmuchbackend.infra.codef.config.CodefProperties;
import com.amateur.howmuchbackend.infra.messaging.kafka.*;

import org.junit.jupiter.api.Test;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.listener.ContainerProperties;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.*;
import java.util.*;

class CertificateReferenceConfigurationTest {
    final ApplicationContextRunner base =
            new ApplicationContextRunner()
                    .withUserConfiguration(CertificateReferenceConfiguration.class);

    @Test
    void disabledFeatureDoesNotRequireNewTablesKafkaOrCertificateKeys() {
        base.run(
                context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean("referencePrerequisites");
                    assertThat(context)
                            .doesNotHaveBean("certificateReferenceKafkaListenerContainerFactory");
                    assertThat(context.getBean(CertificateReferenceProperties.class).enabled())
                            .isFalse();
                });
    }

    @Test
    void missingReviewedSchemaPreventsActivation() {
        enabled(false, true)
                .run(
                        context ->
                                assertThat(context)
                                        .hasFailed()
                                        .getFailure()
                                        .hasRootCauseMessage(
                                                "Review and apply certificate-reference SQL before"
                                                        + " enabling the feature"));
    }

    @Test
    void missingCommonTransportPreventsActivation() {
        enabled(true, false)
                .run(
                        context ->
                                assertThat(context)
                                        .hasFailed()
                                        .getFailure()
                                        .hasRootCauseMessage(
                                                "Certificate reference requires existing"
                                                        + " certificate protection and Kafka"));
    }

    @Test
    void activatedFactoryRetainsCommonDurableConsumerSettings() {
        enabled(true, true)
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            var factory =
                                    context.getBean(
                                            "certificateReferenceKafkaListenerContainerFactory",
                                            ConcurrentKafkaListenerContainerFactory.class);
                            assertThat(factory.getContainerProperties().getAckMode())
                                    .isEqualTo(ContainerProperties.AckMode.RECORD);
                            assertThat(factory.getContainerProperties().isSyncCommits()).isTrue();
                            assertThat(
                                            context.getBean(
                                                            "certificateReferenceExecutionScheduler",
                                                            org.springframework.scheduling
                                                                    .concurrent
                                                                    .ThreadPoolTaskScheduler.class)
                                                    .getScheduledThreadPoolExecutor()
                                                    .getCorePoolSize())
                                    .isEqualTo(4);
                            assertThat(factory.getConsumerFactory().getConfigurationProperties())
                                    .containsEntry("enable.auto.commit", false)
                                    .containsEntry("allow.auto.create.topics", false);
                        });
    }

    @Test
    void topicCollisionAndUnboundedTimeoutPreventActivation() {
        enabled(true, true)
                .withPropertyValues(
                        "howmuch.certificate-reference.command-topic=notification.delivery.v1")
                .run(
                        context ->
                                assertThat(context)
                                        .hasFailed()
                                        .getFailure()
                                        .hasRootCauseMessage(
                                                "Reference topics must not overlap existing"
                                                        + " messaging topics"));
        enabled(true, true)
                .withAllowBeanDefinitionOverriding(true)
                .withBean(
                        CodefProperties.class,
                        () ->
                                new CodefProperties(
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        Duration.ofSeconds(5),
                                        Duration.ZERO))
                .run(
                        context ->
                                assertThat(context)
                                        .hasFailed()
                                        .getFailure()
                                        .hasRootCauseMessage(
                                                "Reference provider timeout must fit its six-minute"
                                                        + " execution window"));
    }

    @Test
    void missingInstitutionAuthenticationColumnsPreventActivation() {
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), anyString())).thenReturn(true);
        when(jdbc.queryForObject(anyString(), eq(Boolean.class))).thenReturn(false);
        enabled(true, true)
                .withAllowBeanDefinitionOverriding(true)
                .withBean(JdbcTemplate.class, () -> jdbc)
                .run(
                        context ->
                                assertThat(context)
                                        .hasFailed()
                                        .getFailure()
                                        .hasRootCauseMessage(
                                                "Apply institution authentication metadata SQL"
                                                    + " before enabling reference connections"));
    }

    ApplicationContextRunner enabled(boolean schema, boolean transport) {
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), anyString())).thenReturn(schema);
        when(jdbc.queryForObject(anyString(), eq(Boolean.class))).thenReturn(schema);
        return base.withPropertyValues("howmuch.certificate-reference.enabled=true")
                .withBean(
                        CertificateProperties.class,
                        () ->
                                new CertificateProperties(
                                        true, "", "", "", List.of(), null, null, null, null, null,
                                        null))
                .withBean(
                        KafkaMessagingProperties.class,
                        () ->
                                new KafkaMessagingProperties(
                                        transport, null, null, 1, null, null, null, false, null,
                                        null, null, false))
                .withBean(
                        CodefProperties.class,
                        () -> new CodefProperties(null, null, null, null, null, null, null, null))
                .withBean(JdbcTemplate.class, () -> jdbc)
                .withBean(KafkaProperties.class, KafkaProperties::new)
                .withBean(AcknowledgedKafkaSender.class, () -> mock(AcknowledgedKafkaSender.class))
                .withBean(ObjectMapper.class, () -> JsonMapper.builder().build())
                .withBean(Clock.class, Clock::systemUTC);
    }
}
