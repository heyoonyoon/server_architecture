package com.serverarchitecture.enrollment;

import com.serverarchitecture.OfferingInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class OfferingCatalog implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(OfferingCatalog.class);

    private final AcademicClient academic;
    private final String semester;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "offering-catalog-refresh");
        thread.setDaemon(true);
        return thread;
    });
    private volatile Map<Integer, Offering> offerings;

    public OfferingCatalog(AcademicClient academic, String semester) {
        this.academic = academic;
        this.semester = semester;
    }

    public void start(int refreshSeconds) {
        refresh();
        scheduler.scheduleWithFixedDelay(this::refresh, refreshSeconds, refreshSeconds, TimeUnit.SECONDS);
    }

    public Map<Integer, Offering> offerings() {
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
        } catch (RuntimeException e) {
            log.warn("개설 강의 캐시 갱신 실패, 이전 목록을 유지합니다: {}", e.getMessage());
        }
    }

    private Map<Integer, Offering> load() {
        Map<Integer, Offering> loaded = new LinkedHashMap<Integer, Offering>();
        for (OfferingInfo info : academic.catalog(semester)) {
            Offering offering = Offering.from(info);
            loaded.put(offering.offeringId(), offering);
        }
        log.info("개설 강의 캐시 갱신 semester={} offerings={}", semester, loaded.size());
        return Collections.unmodifiableMap(loaded);
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }
}
