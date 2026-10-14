package com.serverarchitecture;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;

import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public class EnrollmentEventPublisher implements AutoCloseable {
    private static final long SEND_TIMEOUT_MS = 5000;

    private final Producer<String, byte[]> producer;

    public EnrollmentEventPublisher(Producer<String, byte[]> producer) {
        this.producer = producer;
    }

    public static KafkaProducer<String, byte[]> createProducer(String bootstrapServers) {
        Properties properties = new Properties();
        properties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        properties.put(ProducerConfig.ACKS_CONFIG, "all");
        properties.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        properties.put(ProducerConfig.LINGER_MS_CONFIG, 0);
        properties.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, SEND_TIMEOUT_MS);
        properties.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, (int) SEND_TIMEOUT_MS);
        properties.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, (int) SEND_TIMEOUT_MS);
        properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        return new KafkaProducer<String, byte[]>(properties);
    }

    public void publish(EnrollmentEventType type, String studentId, int offeringId) throws Exception {
        EnrollmentEvent event = EnrollmentEvent.newBuilder()
                .setEventId(UUID.randomUUID().toString())
                .setType(type)
                .setStudentId(studentId)
                .setOfferingId(offeringId)
                .setSemester(EnrollmentConfig.SEMESTER)
                .setOccurredAtMs(System.currentTimeMillis())
                .build();

        producer.send(new ProducerRecord<String, byte[]>(EnrollmentConfig.TOPIC, studentId, event.toByteArray()))
                .get(SEND_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

    @Override
    public void close() {
        producer.close();
    }
}
