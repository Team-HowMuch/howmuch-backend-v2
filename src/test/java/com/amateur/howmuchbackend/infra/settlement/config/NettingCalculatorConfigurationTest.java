package com.amateur.howmuchbackend.infra.settlement.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.amateur.howmuchbackend.application.settlement.netting.calculator.NettingCalculatorFeatures;
import com.amateur.howmuchbackend.application.settlement.netting.calculator.PairReductionSettlementNettingCalculator;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class NettingCalculatorConfigurationTest {
    private final ApplicationContextRunner context =
            new ApplicationContextRunner()
                    .withUserConfiguration(
                            NettingCalculatorConfiguration.class,
                            PairReductionSettlementNettingCalculator.class);

    @Test
    void defaultsToEnabled() {
        context.run(
                c -> {
                    assertThat(c).hasNotFailed().hasSingleBean(NettingCalculatorFeatures.class);
                    assertThat(c.getBean(NettingCalculatorFeatures.class).pairReductionEnabled())
                            .isTrue();
                    assertThat(c).hasSingleBean(PairReductionSettlementNettingCalculator.class);
                });
    }

    @Test
    void disablingAutomaticSelectionDoesNotRemoveThePinnedCalculator() {
        context.withPropertyValues("howmuch.settlement.netting.pair-reduction.enabled=false")
                .run(
                        c -> {
                            assertThat(c).hasNotFailed();
                            assertThat(
                                            c.getBean(NettingCalculatorFeatures.class)
                                                    .pairReductionEnabled())
                                    .isFalse();
                            assertThat(c)
                                    .hasSingleBean(PairReductionSettlementNettingCalculator.class);
                        });
    }
}
