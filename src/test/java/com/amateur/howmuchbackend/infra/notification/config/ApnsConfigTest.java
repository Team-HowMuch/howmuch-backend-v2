package com.amateur.howmuchbackend.infra.notification.config;

import com.amateur.howmuchbackend.adapter.out.push.ApnsPushSender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class ApnsConfigTest {
    @Test
    void springBootJacksonMapperWiresApnsSender() {
        runner().withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
                .withUserConfiguration(ApnsPushSender.class)
                .run(context -> assertThat(context).hasSingleBean(ApnsPushSender.class));
    }

    @ParameterizedTest
    @CsvSource({"local,dev", "dev,dev", "prod,prod"})
    void profileSelectsApnsEnvironment(String profile, String expected) {
        runner().withPropertyValues("spring.profiles.active=" + profile).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(ApnsProperties.class).environment()).isEqualTo(expected);
        });
    }

    @ParameterizedTest
    @CsvSource({"dev,prod", "prod,dev"})
    void explicitEnvironmentOverridesProfile(String profile, String selected) {
        runner().withPropertyValues("spring.profiles.active=" + profile,
                "APNS_ENVIRONMENT=" + selected).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(ApnsProperties.class).environment()).isEqualTo(selected);
        });
    }

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(ApnsConfig.class);
    }
}
