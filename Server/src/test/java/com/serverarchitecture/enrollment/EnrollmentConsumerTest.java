package com.serverarchitecture.enrollment;

import com.serverarchitecture.DataClient;
import com.serverarchitecture.DataCollections;
import com.serverarchitecture.EnrollmentEvent;
import com.serverarchitecture.EnrollmentEventType;
import com.serverarchitecture.data.api.Item;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnrollmentConsumerTest {
    private static final TopicPartition PARTITION = new TopicPartition(EnrollmentConfig.TOPIC, 0);

    private final FakeDataService dataService = new FakeDataService();
    private Server server;
    private ManagedChannel channel;
    private DataClient data;
    private MockConsumer<String, byte[]> kafka;
    private FailingProducer deadLetters;
    private EnrollmentConsumer consumer;
    private final List<ConsumerRecord<String, byte[]>> log = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        String name = InProcessServerBuilder.generateName();
        server = InProcessServerBuilder.forName(name).directExecutor().addService(dataService).build().start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();
        data = new DataClient(channel, 3000);
        kafka = new MockConsumer<>("earliest");
        kafka.assign(List.of(PARTITION));
        kafka.updateBeginningOffsets(Map.of(PARTITION, 0L));
        deadLetters = new FailingProducer();
        consumer = new EnrollmentConsumer(data, kafka, deadLetters, 5, 20);
    }

    @AfterEach
    void tearDown() {
        channel.shutdownNow();
        server.shutdownNow();
    }

    private static EnrollmentEvent event(EnrollmentEventType type, String studentId, int offeringId, long at) {
        return EnrollmentEvent.newBuilder()
                .setEventId(type + "-" + studentId + "-" + offeringId + "-" + at)
                .setType(type)
                .setStudentId(studentId)
                .setOfferingId(offeringId)
                .setSemester("2026-2")
                .setOccurredAtMs(at)
                .build();
    }

    private void append(byte[] value) {
        log.add(new ConsumerRecord<>(EnrollmentConfig.TOPIC, 0, log.size(), "key", value));
    }

    private void append(EnrollmentEvent event) {
        append(event.toByteArray());
    }

    private void pollUntilCommitted(long offset) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 3000;
        while (committed() < offset) {
            assertTrue(System.currentTimeMillis() < deadline, "커밋 대기 시간 초과");
            if (!kafka.paused().contains(PARTITION)) {
                long position = kafka.position(PARTITION);
                for (ConsumerRecord<String, byte[]> record : log) {
                    if (record.offset() >= position) {
                        kafka.addRecord(record);
                    }
                }
            }
            consumer.pollOnce();
            Thread.sleep(2);
        }
    }

    private long committed() {
        OffsetAndMetadata offset = kafka.committed(Set.of(PARTITION)).get(PARTITION);
        return offset == null ? 0 : offset.offset();
    }

    private String status(String id) {
        Item item = dataService.get(DataCollections.ENROLLMENTS, id);
        return item == null ? null : item.getFieldsOrThrow("status");
    }

    @Test
    void applyIsIdempotent() {
        assertEquals("APPLIED", consumer.apply(event(EnrollmentEventType.ENROLLED, "s1", 8, 1)));
        assertEquals("NO_CHANGE", consumer.apply(event(EnrollmentEventType.ENROLLED, "s1", 8, 1)));
        assertEquals("ENROLLED", status("s1:8"));

        assertEquals("APPLIED", consumer.apply(event(EnrollmentEventType.CANCELLED, "s1", 8, 2)));
        assertEquals("NO_CHANGE", consumer.apply(event(EnrollmentEventType.CANCELLED, "s1", 8, 2)));
        assertEquals("CANCELLED", status("s1:8"));

        assertEquals("NO_CHANGE", consumer.apply(event(EnrollmentEventType.CANCELLED, "s2", 9, 3)));
        assertNull(status("s2:9"));

        assertEquals("APPLIED", consumer.apply(event(EnrollmentEventType.ENROLLED, "s1", 8, 4)));
        assertEquals("ENROLLED", status("s1:8"));
        assertEquals("4", dataService.get(DataCollections.ENROLLMENTS, "s1:8").getFieldsOrThrow("applied_at_ms"));
    }

    @Test
    void replayingTheSameEventsConvergesToTheSameState() {
        List<EnrollmentEvent> events = List.of(
                event(EnrollmentEventType.ENROLLED, "s1", 8, 1),
                event(EnrollmentEventType.ENROLLED, "s1", 9, 2),
                event(EnrollmentEventType.CANCELLED, "s1", 8, 3),
                event(EnrollmentEventType.ENROLLED, "s1", 8, 4),
                event(EnrollmentEventType.CANCELLED, "s1", 9, 5));
        events.forEach(consumer::apply);
        Item first8 = dataService.get(DataCollections.ENROLLMENTS, "s1:8");
        Item first9 = dataService.get(DataCollections.ENROLLMENTS, "s1:9");

        events.forEach(consumer::apply);

        assertEquals(first8, dataService.get(DataCollections.ENROLLMENTS, "s1:8"));
        assertEquals(first9, dataService.get(DataCollections.ENROLLMENTS, "s1:9"));
        assertEquals("ENROLLED", status("s1:8"));
        assertEquals("CANCELLED", status("s1:9"));
    }

    @Test
    void duplicateDeliveryIsCommittedWithoutDeadLetter() throws Exception {
        append(event(EnrollmentEventType.ENROLLED, "s1", 8, 1));
        append(event(EnrollmentEventType.ENROLLED, "s1", 8, 1));

        pollUntilCommitted(2);

        assertEquals("ENROLLED", status("s1:8"));
        assertEquals(1, dataService.size(DataCollections.ENROLLMENTS));
        assertTrue(deadLetters.history().isEmpty());
    }

    @Test
    void transientDataFailureIsRetriedWithoutCommitOrDeadLetter() throws Exception {
        append(event(EnrollmentEventType.ENROLLED, "s1", 8, 1));
        append(event(EnrollmentEventType.ENROLLED, "s2", 8, 2));
        dataService.failNext(Status.UNAVAILABLE, 3);
        dataService.failNext(Status.DEADLINE_EXCEEDED, 2);

        kafka.addRecord(log.get(0));
        kafka.addRecord(log.get(1));
        consumer.pollOnce();
        assertEquals(0, committed());
        assertTrue(kafka.paused().contains(PARTITION));

        pollUntilCommitted(2);

        assertEquals("ENROLLED", status("s1:8"));
        assertEquals("ENROLLED", status("s2:8"));
        assertTrue(deadLetters.history().isEmpty());
        assertTrue(kafka.paused().isEmpty());
    }

    @Test
    void permanentFailuresGoToDeadLetterAndAreCommitted() throws Exception {
        append("garbage".getBytes(StandardCharsets.UTF_8));
        append(event(EnrollmentEventType.ENROLLED, "", 8, 1));
        append(event(EnrollmentEventType.ENROLLED, "s1", 8, 2));

        pollUntilCommitted(3);

        assertEquals(2, deadLetters.history().size());
        ProducerRecord<String, byte[]> first = deadLetters.history().get(0);
        assertEquals(EnrollmentConfig.DEAD_LETTER_TOPIC, first.topic());
        assertEquals("0", new String(first.headers().lastHeader("source-offset").value(), StandardCharsets.UTF_8));
        assertTrue(new String(first.headers().lastHeader("error").value(), StandardCharsets.UTF_8)
                .startsWith("PARSE_ERROR"));
        assertEquals("ENROLLED", status("s1:8"));
    }

    @Test
    void nonTransientDataErrorGoesToDeadLetter() throws Exception {
        append(event(EnrollmentEventType.ENROLLED, "s1", 8, 1));
        dataService.failNext(Status.INVALID_ARGUMENT, 1);

        pollUntilCommitted(1);

        assertEquals(1, deadLetters.history().size());
        assertNull(status("s1:8"));
    }

    @Test
    void deadLetterFailureHoldsOffsetUntilItSucceeds() throws Exception {
        append("garbage".getBytes(StandardCharsets.UTF_8));
        deadLetters.failures = 2;

        pollUntilCommitted(1);

        assertEquals(1, deadLetters.history().size());
        assertEquals(0, deadLetters.failures);
    }

    private static final class FailingProducer extends MockProducer<String, byte[]> {
        volatile int failures;

        FailingProducer() {
            super(true, null, new StringSerializer(), new ByteArraySerializer());
        }

        @Override
        public synchronized Future<RecordMetadata> send(ProducerRecord<String, byte[]> record) {
            if (failures > 0) {
                failures--;
                return CompletableFuture.failedFuture(new KafkaException("DLT 연결 실패"));
            }
            return super.send(record);
        }
    }
}
