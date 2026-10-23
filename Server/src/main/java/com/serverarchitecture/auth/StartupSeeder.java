package com.serverarchitecture.auth;

import com.serverarchitecture.DataClient;
import com.serverarchitecture.SeedLoader;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class StartupSeeder {
    private static final Logger log = LoggerFactory.getLogger(StartupSeeder.class);
    private static final int MAX_ATTEMPTS = 30;
    private static final long RETRY_DELAY_MS = 1000;

    private StartupSeeder() {
    }

    public static void seed(DataClient data, String... collections) throws InterruptedException {
        for (String collection : collections) {
            seedWithRetry(data, collection);
        }
    }

    private static void seedWithRetry(DataClient data, String collection) throws InterruptedException {
        for (int attempt = 1; ; attempt++) {
            try {
                SeedLoader.seedIfEmpty(data, collection);
                return;
            } catch (StatusRuntimeException e) {
                Status.Code code = e.getStatus().getCode();
                boolean retryable = code == Status.Code.UNAVAILABLE || code == Status.Code.DEADLINE_EXCEEDED;
                if (!retryable || attempt >= MAX_ATTEMPTS) {
                    throw e;
                }
                log.warn("데이터 서버 대기 중 collection={} attempt={}/{}: {}", collection, attempt, MAX_ATTEMPTS,
                        e.getStatus().getCode());
                Thread.sleep(RETRY_DELAY_MS);
            }
        }
    }
}
