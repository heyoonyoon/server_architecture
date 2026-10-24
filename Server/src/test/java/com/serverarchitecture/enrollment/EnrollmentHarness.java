package com.serverarchitecture.enrollment;

import com.serverarchitecture.AuthInterceptor;
import com.serverarchitecture.AuthenticatedUser;
import com.serverarchitecture.EnrollmentServiceGrpc;
import com.serverarchitecture.JwtProvider;
import com.serverarchitecture.Role;
import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.AbstractStub;
import io.grpc.stub.MetadataUtils;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

class EnrollmentHarness implements AutoCloseable {
    static final Metadata.Key<String> USER_HEADER = Metadata.Key.of("x-user", Metadata.ASCII_STRING_MARSHALLER);

    final FakeAcademicService academicService = new FakeAcademicService();
    final EnrollmentRedis redis;
    final SwitchableProducer producer = new SwitchableProducer();
    final QueueWatcher watcher;
    final EnrollmentServiceImpl service;
    private final Server academicServer;
    private final ManagedChannel academicChannel;
    private final AcademicClient academic;
    private final Server server;
    private final ManagedChannel channel;

    EnrollmentHarness(EnrollmentRedis redis, long watchIntervalMs, int maxWatchers) throws IOException {
        this.redis = redis;
        String academicName = InProcessServerBuilder.generateName();
        academicServer = InProcessServerBuilder.forName(academicName).directExecutor()
                .addService(academicService).build().start();
        academicChannel = InProcessChannelBuilder.forName(academicName).directExecutor().build();
        academic = new AcademicClient(academicChannel,
                new JwtProvider("test-secret-test-secret-test-secret-0123", Duration.ofHours(1)), 3000);
        OfferingCatalog catalog = new OfferingCatalog(academic, "2026-2");
        watcher = new QueueWatcher(watchIntervalMs, maxWatchers, 2);
        service = new EnrollmentServiceImpl(academic, catalog, redis, new EnrollmentEventPublisher(producer),
                watcher, new StudentLock(redis, 10000, 3000));
        String name = InProcessServerBuilder.generateName();
        server = InProcessServerBuilder.forName(name)
                .addService(ServerInterceptors.intercept(service, new UserInterceptor()))
                .build().start();
        channel = InProcessChannelBuilder.forName(name).build();
    }

    EnrollmentServiceGrpc.EnrollmentServiceBlockingStub blocking(String studentId) {
        return withUser(EnrollmentServiceGrpc.newBlockingStub(channel), studentId);
    }

    EnrollmentServiceGrpc.EnrollmentServiceStub async(String studentId) {
        return withUser(EnrollmentServiceGrpc.newStub(channel), studentId);
    }

    private static <S extends AbstractStub<S>> S withUser(S stub, String studentId) {
        Metadata headers = new Metadata();
        headers.put(USER_HEADER, studentId);
        return stub.withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));
    }

    @Override
    public void close() throws Exception {
        channel.shutdownNow();
        server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        watcher.close();
        academic.close();
        academicServer.shutdownNow();
        academicChannel.shutdownNow();
    }

    static final class SwitchableProducer extends MockProducer<String, byte[]> {
        volatile boolean failing;

        SwitchableProducer() {
            super(true, null, new StringSerializer(), new ByteArraySerializer());
        }

        @Override
        public synchronized Future<RecordMetadata> send(ProducerRecord<String, byte[]> record) {
            if (failing) {
                return CompletableFuture.failedFuture(new KafkaException("Kafka 연결 실패"));
            }
            return super.send(record);
        }

        List<ProducerRecord<String, byte[]>> sent() {
            return history();
        }
    }

    private static final class UserInterceptor implements ServerInterceptor {
        @Override
        public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> call, Metadata headers,
                                                                     ServerCallHandler<ReqT, RespT> next) {
            Context context = Context.current()
                    .withValue(AuthInterceptor.USER, new AuthenticatedUser(headers.get(USER_HEADER), Role.STUDENT));
            return Contexts.interceptCall(context, call, headers, next);
        }
    }
}
