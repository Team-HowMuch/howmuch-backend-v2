package com.amateur.howmuchbackend.infra.messaging.kafka;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.jspecify.annotations.Nullable;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/** Transport only: callers own their database Outbox state and retry policy. */
public final class AcknowledgedKafkaSender {
    private final KafkaTemplate<String, String> template;
    private final Duration timeout;
    private final @Nullable MeterRegistry meters;

    public AcknowledgedKafkaSender(KafkaTemplate<String, String> template, Duration timeout) {
        this(template, timeout, null);
    }

    public AcknowledgedKafkaSender(KafkaTemplate<String, String> template, Duration timeout,
                                  @Nullable MeterRegistry meters) {
        this.template = template;
        this.timeout = timeout;
        this.meters = meters;
    }

    public void send(String topic, String key, String payload) {
        var sample = meters == null ? null : Timer.start(meters);
        String result = "failure";
        try {
            template.send(topic, key, payload).get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            result = "acknowledged";
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new KafkaPublicationException();
        } catch (Exception exception) {
            // No raw record, key, broker exception body, or credentials in the exception.
            throw new KafkaPublicationException();
        } finally {
            if (sample != null) {
                sample.stop(Timer.builder("howmuch.messaging.kafka.publish")
                        .tag("destination", topic).tag("result", result).register(meters));
            }
        }
    }
}
