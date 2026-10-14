package com.serverarchitecture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class OfferingCatalog implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(OfferingCatalog.class);

    private final OfferingRepository repository;
    private final String semester;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "offering-catalog-refresh");
        thread.setDaemon(true);
        return thread;
    });
    private volatile Map<Integer, Offering> offerings;

    public OfferingCatalog(OfferingRepository repository, String semester) {
        this.repository = repository;
        this.semester = semester;
    }

    public void start(int refreshSeconds) {
        refresh();
        scheduler.scheduleWithFixedDelay(this::refresh, refreshSeconds, refreshSeconds, TimeUnit.SECONDS);
    }

    public Map<Integer, Offering> offerings() throws SQLException {
        Map<Integer, Offering> current = offerings;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (offerings == null) {
                offerings = load();
            }
            return offerings;
        }
    }

    public void invalidate() {
        scheduler.execute(this::refresh);
    }

    public void refresh() {
        try {
            offerings = load();
        } catch (SQLException | RuntimeException e) {
            log.warn("개설 강의 캐시 갱신 실패, 이전 목록을 유지합니다: {}", e.getMessage());
        }
    }

    private Map<Integer, Offering> load() throws SQLException {
        Map<Integer, Offering> loaded = new LinkedHashMap<Integer, Offering>();
        for (Offering offering : repository.findBySemester(semester).values()) {
            loaded.put(offering.offeringId(), new Offering(offering.offeringId(), offering.courseId(),
                    offering.courseName(), offering.credits(), offering.semester(), offering.section(),
                    offering.professorId(), offering.professorName(), offering.room(), offering.capacity(),
                    offering.enrolledCount(), List.copyOf(offering.times()),
                    List.copyOf(offering.prerequisiteCourseIds())));
        }
        log.info("개설 강의 캐시 갱신 semester={} offerings={}", semester, loaded.size());
        return Collections.unmodifiableMap(loaded);
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }
}
