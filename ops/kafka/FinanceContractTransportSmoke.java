import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.common.serialization.StringDeserializer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** Explicit synthetic transport check. Use only a local/test broker and a chosen client-version classpath. */
class FinanceContractTransportSmoke {
    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("Expected bootstrap, command fixture and result fixture.");
        try (var admin = AdminClient.create(Map.of("bootstrap.servers", args[0]))) {
            if (admin.describeCluster().nodes().get(10, TimeUnit.SECONDS).isEmpty()) throw new AssertionError("No broker metadata.");
        }
        try (var producer = new KafkaProducer<String, String>(Map.of("bootstrap.servers", args[0],
                "key.serializer", StringSerializer.class, "value.serializer", StringSerializer.class,
                "acks", "all", "enable.idempotence", true, "max.block.ms", 3000))) {
            for (var entry : Map.of("finance.sync.command.v1", args[1], "finance.sync.event.v1", args[2]).entrySet()) {
                String payload = Files.readString(Path.of(entry.getValue()));
                var accountRef = Pattern.compile("\"accountRef\"\\s*:\\s*\"([^\"]+)\"").matcher(payload);
                if (!accountRef.find()) throw new AssertionError("Missing synthetic fixture reference.");
                var ack = producer.send(new ProducerRecord<>(entry.getKey(), accountRef.group(1), payload))
                        .get(10, TimeUnit.SECONDS);
                try (var consumer = new KafkaConsumer<String, String>(Map.of("bootstrap.servers", args[0],
                        "key.deserializer", StringDeserializer.class, "value.deserializer", StringDeserializer.class,
                        "group.id", "synthetic-contract-smoke-" + UUID.randomUUID(), "enable.auto.commit", false))) {
                    var partition = new TopicPartition(ack.topic(), ack.partition());
                    consumer.assign(List.of(partition));
                    consumer.seek(partition, ack.offset());
                    boolean found = false;
                    long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
                    while (System.nanoTime() < deadline && !found) {
                        for (var record : consumer.poll(Duration.ofMillis(100))) {
                            if (record.offset() != ack.offset()) continue;
                            if (!accountRef.group(1).equals(record.key()) || !payload.equals(record.value())) {
                                throw new AssertionError("Synthetic fixture changed during transport.");
                            }
                            found = true;
                        }
                    }
                    if (!found) throw new AssertionError("No synthetic fixture round trip.");
                }
            }
        }
        System.out.println("Broker metadata, RC2 command/event ACK and exact payload round trips passed.");
    }
}
