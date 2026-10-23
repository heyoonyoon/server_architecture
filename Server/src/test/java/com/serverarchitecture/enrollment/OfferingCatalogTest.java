package com.serverarchitecture.enrollment;

import com.serverarchitecture.JwtProvider;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OfferingCatalogTest {
    private final FakeAcademicService academicService = new FakeAcademicService();
    private Server server;
    private ManagedChannel channel;
    private OfferingCatalog catalog;

    @BeforeEach
    void setUp() throws Exception {
        String name = InProcessServerBuilder.generateName();
        server = InProcessServerBuilder.forName(name).directExecutor().addService(academicService).build().start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();
        AcademicClient academic = new AcademicClient(channel,
                new JwtProvider("test-secret-test-secret-test-secret-0123", Duration.ofHours(1)), 3000);
        catalog = new OfferingCatalog(academic, "2026-2");
        academicService.offering(8, "CS101", "자료구조", 5, List.of(), "MON", "09:00", "10:30");
    }

    @AfterEach
    void tearDown() {
        catalog.close();
        channel.shutdownNow();
        server.shutdownNow();
    }

    @Test
    void retriesUntilAcademicIsReady() throws Exception {
        academicService.catalogFailures.set(3);

        catalog.start(60);

        long deadline = System.currentTimeMillis() + 10_000;
        while (academicService.catalogCalls.get() < 4 && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertEquals(4, academicService.catalogCalls.get());
        assertEquals(1, catalog.offerings().size());
        assertEquals(4, academicService.catalogCalls.get());
    }

    @Test
    void loadsImmediatelyWhenAcademicIsReady() {
        catalog.start(60);

        assertEquals(1, academicService.catalogCalls.get());
        assertTrue(catalog.offerings().containsKey(8));
    }
}
