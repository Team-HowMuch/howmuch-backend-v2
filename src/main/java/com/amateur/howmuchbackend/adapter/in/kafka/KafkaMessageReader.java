package com.amateur.howmuchbackend.adapter.in.kafka;

import com.amateur.howmuchbackend.infra.messaging.kafka.InvalidKafkaMessageException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.DeserializationFeature;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Strict transport validation; shared only by the two actual inbound adapters. */
public final class KafkaMessageReader {
    private KafkaMessageReader() {}

    public static JsonNode read(ObjectMapper json, String value, Set<String> fields, String type) {
        try {
            if (value == null || value.getBytes(StandardCharsets.UTF_8).length > 4096) throw invalid();
            JsonNode body = json.reader()
                    .with(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY,
                            DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(value);
            if (body == null || !body.isObject()) throw invalid();
            Set<String> actual = new HashSet<>();
            body.propertyNames().forEach(actual::add);
            if (!actual.equals(fields) || !type.equals(text(body, "messageType"))
                    || number(body, "schemaVersion", 1) != 1) throw invalid();
            uuid(body, "messageId");
            utcTime(body, "occurredAt");
            return body;
        } catch (Exception exception) {
            throw invalid();
        }
    }

    public static UUID uuid(JsonNode body, String name) {
        try {
            String value = text(body, name);
            UUID parsed = UUID.fromString(value);
            if (!parsed.toString().equals(value)) throw invalid();
            return parsed;
        } catch (RuntimeException exception) {
            throw invalid();
        }
    }

    public static long number(JsonNode body, String name, long minimum) {
        JsonNode value = body.path(name);
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < minimum) throw invalid();
        return value.longValue();
    }

    public static String text(JsonNode body, String name) {
        JsonNode value = body.path(name);
        if (!value.isString()) throw invalid();
        return value.stringValue();
    }

    public static LocalDateTime utcTime(JsonNode body, String name) {
        try {
            String value = text(body, name);
            if (value.length() > 40 || !value.endsWith("Z")) throw invalid();
            return LocalDateTime.ofInstant(Instant.parse(value), ZoneOffset.UTC);
        } catch (RuntimeException exception) {
            throw invalid();
        }
    }

    public static InvalidKafkaMessageException invalid() {
        return new InvalidKafkaMessageException();
    }
}
