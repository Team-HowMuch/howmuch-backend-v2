package com.amateur.howmuchbackend.infra.settlement;

import com.amateur.howmuchbackend.application.port.in.settlement.SendAutomaticSettlementRemindersUseCase;
import com.amateur.howmuchbackend.infra.settlement.config.SettlementAutomaticReminderProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import java.time.Duration;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class SettlementAutomaticReminderConfigurationTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfig.class, SettlementAutomaticReminderScheduler.class)
            .withBean(SendAutomaticSettlementRemindersUseCase.class,
                    () -> mock(SendAutomaticSettlementRemindersUseCase.class));

    @Test
    void disabledByDefaultWithOneMinuteAndFiftyDefaults() {
        context.run(app -> {
            assertThat(app).doesNotHaveBean(SettlementAutomaticReminderScheduler.class);
            var properties = app.getBean(SettlementAutomaticReminderProperties.class);
            assertThat(properties.enabled()).isFalse();
            assertThat(properties.pollInterval()).isEqualTo(Duration.ofMinutes(1));
            assertThat(properties.batchSize()).isEqualTo(50);
        });
    }

    @Test
    void explicitEnableRegistersSchedulerAndOverridesBatch() {
        context.withPropertyValues("howmuch.settlement.automatic-reminder.enabled=true",
                "howmuch.settlement.automatic-reminder.batch-size=10").run(app -> {
            assertThat(app).hasSingleBean(SettlementAutomaticReminderScheduler.class);
            assertThat(app.getBean(SettlementAutomaticReminderProperties.class).batchSize()).isEqualTo(10);
        });
    }

    @Test
    void invalidIntervalFailsStartup() {
        context.withPropertyValues("howmuch.settlement.automatic-reminder.poll-interval=-1s")
                .run(app -> assertThat(app).hasFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(SettlementAutomaticReminderProperties.class)
    static class PropertiesConfig {}
}
