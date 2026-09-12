package com.amateur.howmuchbackend.infra.observability;

import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

import java.util.Locale;

@Configuration(proxyBeanMethods = false)
public class ObservabilityConfig {
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    ObservationHandler<Observation.Context> safeObservationHandler() {
        return new ObservationHandler<>() {
            @Override
            public void onError(Observation.Context context) {
                context.setError(null);
            }

            @Override
            public void onStop(Observation.Context context) {
                context.getLowCardinalityKeyValues().stream()
                        .map(KeyValue::getKey)
                        .filter(ObservabilityConfig::isSensitiveKey)
                        .forEach(context::removeLowCardinalityKeyValue);
                context.getHighCardinalityKeyValues().stream()
                        .map(KeyValue::getKey)
                        .filter(ObservabilityConfig::isSensitiveKey)
                        .forEach(context::removeHighCardinalityKeyValue);
                context.setError(null);
            }

            @Override
            public boolean supportsContext(Observation.Context context) {
                return true;
            }
        };
    }

    private static boolean isSensitiveKey(String key) {
        String normalized = key.toLowerCase(Locale.ROOT);
        return normalized.equals("http.url")
                || normalized.contains("query")
                || normalized.contains("statement")
                || normalized.contains("params")
                || normalized.contains("payload")
                || normalized.contains("body")
                || normalized.contains("header")
                || normalized.contains("redis")
                || normalized.contains("credential")
                || normalized.contains("token")
                || normalized.contains("secret")
                || normalized.contains("password")
                || normalized.contains("stacktrace")
                || normalized.contains("exception.message");
    }
}
