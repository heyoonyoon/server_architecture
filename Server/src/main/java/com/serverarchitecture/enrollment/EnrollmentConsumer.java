package com.serverarchitecture.enrollment;

import com.google.protobuf.InvalidProtocolBufferException;
import com.serverarchitecture.DataClient;
import com.serverarchitecture.DataCollections;
import com.serverarchitecture.EnrollmentEvent;
import com.serverarchitecture.data.api.UpsertOutcome;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class EnrollmentConsumer {
    private static final Logger log = LoggerFactory.getLogger(EnrollmentConsumer.class);
    private static final String GROUP_ID = "enrollment-consumer";
    private static final long INITIAL_BACKOFF_MS = 500;
    private static final long MAX_BACKOFF_MS = 8000;
    private static final Duration POLL_TIMEOUT = Duration.ofSeconds(1);
    private static final Set<Status.Code> TRANSIENT_CODES = Set.of(Status.Code.UNAVAILABLE,
            Status.Code.DEADLINE_EXCEEDED, Status.Code.RESOURCE_EXHAUSTED, Status.Code.ABORTED);

    private final DataClient data;
    private final Consumer<String, byte[]> consumer;
    private final Producer<String, byte[]> deadLetterProducer;
    private final long initialBackoffMs;
    private final long maxBackoffMs;
    private final Map<TopicPartition, Backoff> backoffs = new HashMap<TopicPartition, Backoff>();

    public EnrollmentConsumer(DataClient data,
                              Consumer<String, byte[]> consumer,
                              Producer<String, byte[]> deadLetterProducer) {
        this(data, consumer, deadLetterProducer, INITIAL_BACKOFF_MS, MAX_BACKOFF_MS);
    }

    EnrollmentConsumer(DataClient data,
                       Consumer<String, byte[]> consumer,
                       Producer<String, byte[]> deadLetterProducer,
                       long initialBackoffMs,
                       long maxBackoffMs) {
        this.data = data;
        this.consumer = consumer;
        this.deadLetterProducer = deadLetterProducer;
        this.initialBackoffMs = initialBackoffMs;
        this.maxBackoffMs = maxBackoffMs;
    }

    public static void main(String[] args) throws Exception {
        log.info("수강신청 이벤트 컨슈머 시작 bootstrap={} topic={} group={}",
                EnrollmentConfig.KAFKA_BOOTSTRAP, EnrollmentConfig.TOPIC, GROUP_ID);

        DataClient data = DataClient.fromEnv();
        KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(consumerProperties());
        KafkaProducer<String, byte[]> producer = new KafkaProducer<>(producerProperties());
        EnrollmentConsumer enrollmentConsumer = new EnrollmentConsumer(data, consumer, producer);

        CountDownLatch stopped = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("수강신청 이벤트 컨슈머 종료 중");
            consumer.wakeup();
            try {
                stopped.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "enrollment-consumer-shutdown"));

        try {
            enrollmentConsumer.run();
        } finally {
            consumer.close();
            producer.close();
            data.close();
            log.info("수강신청 이벤트 컨슈머 종료");
            stopped.countDown();
        }
    }

    private static Properties consumerProperties() {
        Properties properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, EnrollmentConfig.KAFKA_BOOTSTRAP);
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, GROUP_ID);
        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, "100");
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        return properties;
    }

    private static Properties producerProperties() {
        Properties properties = new Properties();
        properties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, EnrollmentConfig.KAFKA_BOOTSTRAP);
        properties.put(ProducerConfig.ACKS_CONFIG, "all");
        properties.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true");
        properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        return properties;
    }

    public void run() {
        consumer.subscribe(List.of(EnrollmentConfig.TOPIC), new ConsumerRebalanceListener() {
            @Override
            public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
                partitions.forEach(backoffs::remove);
            }

            @Override
            public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
            }
        });
        try {
            while (true) {
                pollOnce();
            }
        } catch (WakeupException e) {
            log.info("폴링 중단");
        }
    }

    void pollOnce() {
        resumeDuePartitions();
        ConsumerRecords<String, byte[]> records = consumer.poll(POLL_TIMEOUT);
        for (TopicPartition partition : records.partitions()) {
            processPartition(partition, records.records(partition));
        }
    }

    private void resumeDuePartitions() {
        long now = System.currentTimeMillis();
        Set<TopicPartition> assigned = consumer.assignment();
        List<TopicPartition> due = new ArrayList<TopicPartition>();
        for (Map.Entry<TopicPartition, Backoff> entry : backoffs.entrySet()) {
            if (entry.getValue().paused && entry.getValue().resumeAt <= now && assigned.contains(entry.getKey())) {
                entry.getValue().paused = false;
                due.add(entry.getKey());
            }
        }
        if (!due.isEmpty()) {
            consumer.resume(due);
        }
    }

    private void processPartition(TopicPartition partition, List<ConsumerRecord<String, byte[]>> records) {
        long nextOffset = -1;
        try {
            for (ConsumerRecord<String, byte[]> record : records) {
                try {
                    process(record);
                } catch (RetryLaterException e) {
                    retryLater(partition, record, e.getMessage());
                    return;
                }
                nextOffset = record.offset() + 1;
                Backoff backoff = backoffs.remove(partition);
                if (backoff != null) {
                    log.info("재시도 성공 partition={} offset={} 실패 {}회 후", partition, record.offset(), backoff.failures);
                }
            }
        } finally {
            if (nextOffset >= 0) {
                consumer.commitSync(Map.of(partition, new OffsetAndMetadata(nextOffset)));
            }
        }
    }

    private void retryLater(TopicPartition partition, ConsumerRecord<String, byte[]> record, String error) {
        Backoff backoff = backoffs.computeIfAbsent(partition, key -> new Backoff());
        backoff.failures++;
        long delay = Math.min(maxBackoffMs, initialBackoffMs << Math.min(backoff.failures - 1, 20));
        backoff.resumeAt = System.currentTimeMillis() + delay;
        backoff.paused = true;
        consumer.seek(partition, record.offset());
        consumer.pause(List.of(partition));
        log.warn("일시 오류, {}ms 후 재시도 {}회째 partition={} offset={}: {}",
                delay, backoff.failures, partition, record.offset(), error);
    }

    private void process(ConsumerRecord<String, byte[]> record) throws RetryLaterException {
        EnrollmentEvent event;
        try {
            event = EnrollmentEvent.parseFrom(record.value());
        } catch (InvalidProtocolBufferException | NullPointerException e) {
            log.error("이벤트 해석 실패 partition={} offset={}: {}", record.partition(), record.offset(), e.getMessage());
            sendToDeadLetter(record, "PARSE_ERROR: " + e.getMessage());
            return;
        }

        String error;
        try {
            String outcome = apply(event);
            log.info("이벤트 처리 eventId={} type={} student={} offering={} outcome={} partition={} offset={}",
                    event.getEventId(), event.getType(), event.getStudentId(), event.getOfferingId(),
                    outcome, record.partition(), record.offset());
            return;
        } catch (StatusRuntimeException e) {
            if (TRANSIENT_CODES.contains(e.getStatus().getCode())) {
                throw new RetryLaterException("Data 서버 " + e.getStatus().getCode() + ": " + e.getStatus().getDescription());
            }
            error = e.getStatus().getCode() + ": " + e.getStatus().getDescription();
        } catch (IllegalArgumentException e) {
            error = e.getMessage();
        }

        log.error("이벤트 처리 실패 eventId={} type={} student={} offering={} outcome=DEAD_LETTER error={}",
                event.getEventId(), event.getType(), event.getStudentId(), event.getOfferingId(), error);
        sendToDeadLetter(record, error);
    }

    String apply(EnrollmentEvent event) {
        if (event.getStudentId().isBlank() || event.getOfferingId() <= 0) {
            throw new IllegalArgumentException("학번 또는 개설 강의 id가 없는 이벤트");
        }
        String id = event.getStudentId() + ":" + event.getOfferingId();
        Map<String, String> fields = Map.of(
                "student_id", event.getStudentId(),
                "offering_id", String.valueOf(event.getOfferingId()),
                "semester", event.getSemester(),
                "status", event.getType().name(),
                "applied_at_ms", String.valueOf(event.getOccurredAtMs()));
        return switch (event.getType()) {
            case ENROLLED -> data.upsert(DataCollections.ENROLLMENTS, DataClient.item(id, fields),
                    Map.of("status", "CANCELLED")) == UpsertOutcome.UNCHANGED ? "NO_CHANGE" : "APPLIED";
            case CANCELLED -> cancel(id, fields);
            default -> throw new IllegalArgumentException("알 수 없는 이벤트 유형: " + event.getType());
        };
    }

    private String cancel(String id, Map<String, String> fields) {
        try {
            data.update(DataCollections.ENROLLMENTS, DataClient.item(id, fields), Map.of("status", "ENROLLED"));
            return "APPLIED";
        } catch (StatusRuntimeException e) {
            Status.Code code = e.getStatus().getCode();
            if (code == Status.Code.NOT_FOUND || code == Status.Code.FAILED_PRECONDITION) {
                return "NO_CHANGE";
            }
            throw e;
        }
    }

    private void sendToDeadLetter(ConsumerRecord<String, byte[]> record, String error) throws RetryLaterException {
        ProducerRecord<String, byte[]> deadLetter = new ProducerRecord<>(EnrollmentConfig.DEAD_LETTER_TOPIC, record.key(), record.value());
        deadLetter.headers()
                .add("source-topic", record.topic().getBytes(StandardCharsets.UTF_8))
                .add("source-partition", String.valueOf(record.partition()).getBytes(StandardCharsets.UTF_8))
                .add("source-offset", String.valueOf(record.offset()).getBytes(StandardCharsets.UTF_8))
                .add("error", String.valueOf(error).getBytes(StandardCharsets.UTF_8));
        try {
            deadLetterProducer.send(deadLetter).get(5, TimeUnit.SECONDS);
            log.warn("DLT 전송 topic={} sourcePartition={} sourceOffset={} key={}",
                    EnrollmentConfig.DEAD_LETTER_TOPIC, record.partition(), record.offset(), record.key());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RetryLaterException("DLT 전송 중단");
        } catch (Exception e) {
            throw new RetryLaterException("DLT 전송 실패: " + e.getMessage());
        }
    }

    private static final class Backoff {
        private int failures;
        private long resumeAt;
        private boolean paused;
    }

    private static class RetryLaterException extends Exception {
        RetryLaterException(String message) {
            super(message);
        }
    }
}
