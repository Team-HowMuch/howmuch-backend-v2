package com.amateur.howmuchbackend.infra.notification.config;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class ApnsConfigTest {
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
