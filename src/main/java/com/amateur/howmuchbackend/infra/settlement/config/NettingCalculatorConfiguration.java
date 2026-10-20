package com.amateur.howmuchbackend.infra.settlement.config;

import com.amateur.howmuchbackend.application.settlement.netting.calculator.NettingCalculatorFeatures;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class NettingCalculatorConfiguration {
    @Bean
    NettingCalculatorFeatures nettingCalculatorFeatures(
            @Value("${howmuch.settlement.netting.pair-reduction.enabled:true}") boolean enabled) {
        return new NettingCalculatorFeatures(enabled);
    }
}
