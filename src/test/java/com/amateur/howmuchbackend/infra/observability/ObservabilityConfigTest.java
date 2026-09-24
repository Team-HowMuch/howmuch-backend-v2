package com.amateur.howmuchbackend.infra.observability;

import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ObservabilityConfigTest {
    private final ObservationHandler<Observation.Context> handler =
            new ObservabilityConfig().safeObservationHandler();

    @Test
    void observability_sensitiveAttributesAndErrors_removedBeforeStop() {
        var context = new Observation.Context();
        context.addLowCardinalityKeyValue(KeyValue.of("http.method", "POST"));
        context.addLowCardinalityKeyValue(KeyValue.of("http.url", "https://provider.test?token=secret"));
        context.addHighCardinalityKeyValue(KeyValue.of("db.query.text", "select secret"));
        context.addHighCardinalityKeyValue(KeyValue.of("custom", "safe"));
        context.setError(new IllegalStateException("secret"));

        handler.onStop(context);

        assertThat(context.getLowCardinalityKeyValues()).extracting(KeyValue::getKey)
                .containsExactly("http.method");
        assertThat(context.getHighCardinalityKeyValues()).extracting(KeyValue::getKey)
                .containsExactly("custom");
        assertThat(context.getError()).isNull();
    }
}
