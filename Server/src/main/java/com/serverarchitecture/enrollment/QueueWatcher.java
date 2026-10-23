package com.serverarchitecture.enrollment;

import com.serverarchitecture.QueueState;
import com.serverarchitecture.QueueStatusResponse;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.ServerCallStreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;

public class QueueWatcher implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(QueueWatcher.class);
    private static final int MAX_CONSECUTIVE_FAILURES = 3;

    private final ScheduledExecutorService scheduler;
    private final long intervalMillis;
    private final int maxWatchers;
    private final AtomicInteger active = new AtomicInteger();

    public QueueWatcher(long intervalMillis, int maxWatchers, int threads) {
        AtomicInteger threadNumber = new AtomicInteger();
        this.scheduler = Executors.newScheduledThreadPool(threads, runnable -> {
            Thread thread = new Thread(runnable, "queue-watch-" + threadNumber.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        this.intervalMillis = intervalMillis;
        this.maxWatchers = maxWatchers;
    }

    public int activeWatchers() {
        return active.get();
    }

    public void watch(ServerCallStreamObserver<QueueStatusResponse> observer,
                      Supplier<QueueStatusResponse> status,
                      Function<RuntimeException, StatusRuntimeException> errors) {
        if (active.incrementAndGet() > maxWatchers) {
            active.decrementAndGet();
            observer.onError(Status.RESOURCE_EXHAUSTED
                    .withDescription("대기열 화면 연결이 많습니다. 잠시 후 다시 시도하세요.")
                    .asRuntimeException());
            return;
        }
        Watch watch = new Watch(observer, status, errors);
        observer.setOnCancelHandler(watch::close);
        watch.start();
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }

    private final class Watch {
        private final ServerCallStreamObserver<QueueStatusResponse> observer;
        private final Supplier<QueueStatusResponse> status;
        private final Function<RuntimeException, StatusRuntimeException> errors;
        private final AtomicBoolean closed = new AtomicBoolean();
        private volatile ScheduledFuture<?> task;
        private QueueStatusResponse last;
        private int failures;

        private Watch(ServerCallStreamObserver<QueueStatusResponse> observer,
                      Supplier<QueueStatusResponse> status,
                      Function<RuntimeException, StatusRuntimeException> errors) {
            this.observer = observer;
            this.status = status;
            this.errors = errors;
        }

        private void start() {
            if (!check(true)) {
                return;
            }
            try {
                task = scheduler.scheduleWithFixedDelay(() -> check(false),
                        intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
            } catch (RuntimeException e) {
                fail(Status.UNAVAILABLE.withDescription("수강신청 서버가 종료 중입니다.").asRuntimeException());
                return;
            }
            if (closed.get()) {
                task.cancel(false);
            }
        }

        private synchronized boolean check(boolean first) {
            if (closed.get()) {
                return false;
            }
            if (observer.isCancelled()) {
                close();
                return false;
            }
            QueueStatusResponse current;
            try {
                current = status.get();
                failures = 0;
            } catch (RuntimeException e) {
                failures++;
                if (first || failures >= MAX_CONSECUTIVE_FAILURES) {
                    fail(errors.apply(e));
                    return false;
                }
                log.warn("대기열 상태 확인 실패 {}/{}: {}", failures, MAX_CONSECUTIVE_FAILURES, e.getMessage());
                return true;
            }

            try {
                if (last == null || changed(last, current)) {
                    last = current;
                    observer.onNext(current);
                }
                if (current.getState() != QueueState.WAITING) {
                    observer.onCompleted();
                    close();
                    return false;
                }
                return true;
            } catch (RuntimeException e) {
                log.debug("대기열 스트림 전송 실패: {}", e.getMessage());
                close();
                return false;
            }
        }

        private boolean changed(QueueStatusResponse before, QueueStatusResponse after) {
            return before.getState() != after.getState() || before.getPosition() != after.getPosition();
        }

        private void fail(StatusRuntimeException error) {
            if (closed.get()) {
                return;
            }
            try {
                observer.onError(error);
            } catch (RuntimeException e) {
                log.debug("대기열 스트림 오류 전송 실패: {}", e.getMessage());
            }
            close();
        }

        private void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            ScheduledFuture<?> current = task;
            if (current != null) {
                current.cancel(false);
            }
            active.decrementAndGet();
        }
    }
}
