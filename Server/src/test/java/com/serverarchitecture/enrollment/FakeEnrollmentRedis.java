package com.serverarchitecture.enrollment;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

class FakeEnrollmentRedis extends EnrollmentRedis {
    private final Map<String, Long> active = new HashMap<>();
    private final List<String> queue = new ArrayList<>();
    private final Map<String, Long> seen = new HashMap<>();
    private final Map<Integer, Integer> seats = new HashMap<>();
    private final Map<String, Set<Integer>> studentOfferings = new HashMap<>();
    private final Map<String, String> locks = new HashMap<>();
    private final Map<String, Long> lockExpiresAt = new HashMap<>();
    final AtomicInteger seenMarks = new AtomicInteger();
    final AtomicInteger statusReads = new AtomicInteger();
    volatile long readDelayMs;
    volatile RuntimeException failure;

    FakeEnrollmentRedis() {
        super(null, 300);
    }

    synchronized void admit(String studentId, long expiresAt) {
        queue.remove(studentId);
        active.put(studentId, expiresAt);
    }

    synchronized void queue(String... studentIds) {
        for (String studentId : studentIds) {
            if (!queue.contains(studentId)) {
                queue.add(studentId);
            }
        }
    }

    synchronized void dequeue(String studentId) {
        queue.remove(studentId);
    }

    synchronized void seats(int offeringId, int remaining) {
        seats.put(offeringId, remaining);
    }

    synchronized Long seenAt(String studentId) {
        return seen.get(studentId);
    }

    private void maybeFail() {
        RuntimeException current = failure;
        if (current != null) {
            throw current;
        }
    }

    @Override
    public synchronized Long passExpiresAt(String studentId, long now) {
        maybeFail();
        statusReads.incrementAndGet();
        Long expiresAt = active.get(studentId);
        return expiresAt != null && expiresAt > now ? expiresAt : null;
    }

    @Override
    public synchronized boolean extendPass(String studentId, long now) {
        maybeFail();
        Long expiresAt = active.get(studentId);
        if (expiresAt == null || expiresAt <= now) {
            return false;
        }
        active.put(studentId, now + 300_000);
        return true;
    }

    @Override
    public synchronized Long queueRank(String studentId) {
        maybeFail();
        int index = queue.indexOf(studentId);
        return index < 0 ? null : (long) index;
    }

    @Override
    public synchronized void enterQueue(String studentId, long now) {
        maybeFail();
        queue(studentId);
        seen.put(studentId, now);
    }

    @Override
    public synchronized void markSeen(String studentId, long now) {
        maybeFail();
        seenMarks.incrementAndGet();
        seen.put(studentId, now);
    }

    @Override
    public synchronized boolean leaveQueue(String studentId) {
        boolean removed = queue.remove(studentId) | active.remove(studentId) != null;
        seen.remove(studentId);
        return removed;
    }

    @Override
    public Long openAt() {
        return null;
    }

    @Override
    public Set<Integer> studentOfferings(String studentId) {
        Set<Integer> copy;
        synchronized (this) {
            maybeFail();
            copy = new LinkedHashSet<>(studentOfferings.getOrDefault(studentId, Set.of()));
        }
        if (readDelayMs > 0) {
            try {
                Thread.sleep(readDelayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return copy;
    }

    @Override
    public synchronized Map<Integer, Integer> remainingSeats() {
        return new HashMap<>(seats);
    }

    @Override
    public synchronized long enroll(String studentId, int offeringId) {
        maybeFail();
        Set<Integer> enrolled = studentOfferings.computeIfAbsent(studentId, key -> new LinkedHashSet<>());
        if (enrolled.contains(offeringId)) {
            return -1;
        }
        Integer remaining = seats.get(offeringId);
        if (remaining == null) {
            return -3;
        }
        if (remaining <= 0) {
            return -2;
        }
        seats.put(offeringId, remaining - 1);
        enrolled.add(offeringId);
        return 1;
    }

    @Override
    public synchronized long cancel(String studentId, int offeringId) {
        maybeFail();
        Set<Integer> enrolled = studentOfferings.getOrDefault(studentId, new LinkedHashSet<>());
        if (!enrolled.remove(offeringId)) {
            return 0;
        }
        seats.merge(offeringId, 1, Integer::sum);
        return 1;
    }

    @Override
    public synchronized boolean tryLockStudent(String studentId, String token, long ttlMillis) {
        maybeFail();
        long now = System.currentTimeMillis();
        Long expiresAt = lockExpiresAt.get(studentId);
        if (expiresAt != null && expiresAt > now) {
            return false;
        }
        locks.put(studentId, token);
        lockExpiresAt.put(studentId, now + ttlMillis);
        return true;
    }

    @Override
    public synchronized boolean unlockStudent(String studentId, String token) {
        if (!token.equals(locks.get(studentId))) {
            return false;
        }
        locks.remove(studentId);
        lockExpiresAt.remove(studentId);
        return true;
    }

    synchronized boolean locked(String studentId) {
        return locks.containsKey(studentId);
    }
}
