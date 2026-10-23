package com.serverarchitecture.enrollment;

import com.serverarchitecture.CancelEnrollmentRequest;
import com.serverarchitecture.EnrollRequest;
import com.serverarchitecture.EnrollResponse;
import com.serverarchitecture.EnrollmentEvent;
import com.serverarchitecture.EnrollmentEventType;
import com.serverarchitecture.EnrollmentServiceGrpc;
import com.serverarchitecture.ListMyEnrollmentsRequest;
import com.serverarchitecture.ListMyEnrollmentsResponse;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnrollmentServiceImplTest {
    private static final String STUDENT = "60220001";

    private FakeEnrollmentRedis redis;
    private EnrollmentHarness harness;
    private EnrollmentServiceGrpc.EnrollmentServiceBlockingStub stub;

    @BeforeEach
    void setUp() throws Exception {
        redis = new FakeEnrollmentRedis();
        harness = new EnrollmentHarness(redis, 20, 10);
        FakeAcademicService academic = harness.academicService;
        academic.student(STUDENT, 9);
        academic.offering(8, "C1", "자바", 30, List.of(), "MON", "09:00", "10:30");
        academic.offering(9, "C2", "C++", 30, List.of(), "MON", "10:00", "11:30");
        academic.offering(10, "C1", "자바", 30, List.of(), "TUE", "09:00", "10:30");
        academic.offering(11, "C3", "자료구조", 30, List.of("C1"), "WED", "09:00", "10:30");
        academic.offering(12, "C4", "운영체제", 1, List.of(), "THU", "09:00", "10:30");
        academic.offering(13, "C5", "네트워크", 30, List.of(), "FRI", "09:00", "10:30");
        academic.offering(14, "C6", "보안", 30, List.of(), "FRI", "13:00", "14:30");
        academic.offering(15, "C1", "자바", 30, List.of(), "MON", "09:00", "10:30");
        academic.offering(16, "C7", "데이터베이스", 30, List.of(), "SAT", "09:00", "10:30");
        for (int id = 8; id <= 15; id++) {
            redis.seats(id, id == 12 ? 0 : 30);
        }
        redis.admit(STUDENT, System.currentTimeMillis() + 60_000);
        stub = harness.blocking(STUDENT);
    }

    @AfterEach
    void tearDown() throws Exception {
        harness.close();
    }

    private StatusRuntimeException enrollFails(String studentId, int offeringId) {
        return assertThrows(StatusRuntimeException.class,
                () -> harness.blocking(studentId).enroll(EnrollRequest.newBuilder().setOfferingId(offeringId).build()));
    }

    private EnrollResponse enroll(int offeringId) {
        return stub.enroll(EnrollRequest.newBuilder().setOfferingId(offeringId).build());
    }

    private static void assertStatus(Status.Code code, String text, StatusRuntimeException e) {
        assertEquals(code, e.getStatus().getCode(), e.getStatus().getDescription());
        assertTrue(e.getStatus().getDescription().contains(text), e.getStatus().getDescription());
    }

    @Test
    void passIsCheckedBeforeAnything() {
        StatusRuntimeException e = enrollFails("nopass", 8);

        assertStatus(Status.Code.FAILED_PRECONDITION, "입장권", e);
        assertEquals(0, harness.academicService.studentCalls.get());
    }

    @Test
    void unknownStudentIsNotFound() {
        redis.admit("ghost", System.currentTimeMillis() + 60_000);

        assertStatus(Status.Code.NOT_FOUND, "ghost", enrollFails("ghost", 8));
    }

    @Test
    void unknownOfferingIsNotFound() {
        assertStatus(Status.Code.NOT_FOUND, "존재하지 않는 강의", enrollFails(STUDENT, 999));
    }

    @Test
    void missingPrerequisiteIsAbortedBeforeSeatCheck() {
        redis.seats(11, 0);

        assertStatus(Status.Code.ABORTED, "선수과목(C1)", enrollFails(STUDENT, 11));
    }

    @Test
    void duplicateIsAlreadyExists() {
        enroll(8);

        assertStatus(Status.Code.ALREADY_EXISTS, "이미 신청한 강의", enrollFails(STUDENT, 8));
    }

    @Test
    void timeConflictIsAbortedAndWinsOverSameCourse() {
        enroll(8);

        assertStatus(Status.Code.ABORTED, "자바", enrollFails(STUDENT, 9));
        assertStatus(Status.Code.ABORTED, "시간이 겹쳐", enrollFails(STUDENT, 15));
    }

    @Test
    void sameCourseOtherSectionIsAlreadyExists() {
        enroll(8);

        assertStatus(Status.Code.ALREADY_EXISTS, "같은 과목(자바)", enrollFails(STUDENT, 10));
    }

    @Test
    void creditLimitIsOutOfRangeAndCheckedBeforeSeats() {
        enroll(8);
        enroll(13);
        enroll(14);

        StatusRuntimeException e = enrollFails(STUDENT, 12);

        assertStatus(Status.Code.OUT_OF_RANGE, "9학점", e);
    }

    @Test
    void fullOfferingIsResourceExhausted() {
        assertStatus(Status.Code.RESOURCE_EXHAUSTED, "정원이 마감", enrollFails(STUDENT, 12));
    }

    @Test
    void offeringWithoutSeatsKeyIsUnavailable() {
        assertStatus(Status.Code.UNAVAILABLE, "준비 중", enrollFails(STUDENT, 16));
    }

    @Test
    void successPublishesEventAndTakesSeat() throws Exception {
        EnrollResponse response = enroll(8);

        assertEquals(8, response.getOffering().getOfferingId());
        assertEquals(29, response.getOffering().getRemaining());
        assertEquals(Set.of(8), redis.studentOfferings(STUDENT));
        EnrollmentEvent event = EnrollmentEvent.parseFrom(harness.producer.sent().get(0).value());
        assertEquals(EnrollmentEventType.ENROLLED, event.getType());
        assertEquals(STUDENT, event.getStudentId());
        assertFalse(redis.locked(STUDENT));
    }

    @Test
    void publishFailureRollsBackRedisAndReleasesLock() {
        harness.producer.failing = true;

        assertStatus(Status.Code.UNAVAILABLE, "다시 시도", enrollFails(STUDENT, 8));

        assertEquals(Set.of(), redis.studentOfferings(STUDENT));
        assertEquals(30, redis.remainingSeats().get(8));
        assertFalse(redis.locked(STUDENT));
    }

    @Test
    void cancelWithoutEnrollmentIsNotFound() {
        StatusRuntimeException e = assertThrows(StatusRuntimeException.class,
                () -> stub.cancelEnrollment(CancelEnrollmentRequest.newBuilder().setOfferingId(8).build()));

        assertStatus(Status.Code.NOT_FOUND, "신청 내역", e);
    }

    @Test
    void cancelPublishFailureKeepsEnrollment() {
        enroll(8);
        harness.producer.failing = true;

        StatusRuntimeException e = assertThrows(StatusRuntimeException.class,
                () -> stub.cancelEnrollment(CancelEnrollmentRequest.newBuilder().setOfferingId(8).build()));

        assertStatus(Status.Code.UNAVAILABLE, "다시 시도", e);
        assertEquals(Set.of(8), redis.studentOfferings(STUDENT));
        assertEquals(29, redis.remainingSeats().get(8));
    }

    @Test
    void cancelReturnsSeatAndPublishes() throws Exception {
        enroll(8);

        stub.cancelEnrollment(CancelEnrollmentRequest.newBuilder().setOfferingId(8).build());

        assertEquals(Set.of(), redis.studentOfferings(STUDENT));
        assertEquals(30, redis.remainingSeats().get(8));
        EnrollmentEvent event = EnrollmentEvent.parseFrom(harness.producer.sent().get(1).value());
        assertEquals(EnrollmentEventType.CANCELLED, event.getType());
    }

    @Test
    void concurrentConflictingEnrollsBySameStudentGiveExactlyOneSuccess() throws Exception {
        List<Status.Code> results = raceConflictingEnrolls();

        assertEquals(1, results.stream().filter(code -> code == Status.Code.OK).count(), results.toString());
        assertEquals(1, results.stream().filter(code -> code == Status.Code.ABORTED).count(), results.toString());
        assertEquals(1, redis.studentOfferings(STUDENT).size());
        assertEquals(59, redis.remainingSeats().get(8) + redis.remainingSeats().get(9));
    }

    @Test
    void raceWindowIsRealWithoutTheLock() throws Exception {
        FakeEnrollmentRedis unlocked = new FakeEnrollmentRedis() {
            @Override
            public synchronized boolean tryLockStudent(String studentId, String token, long ttlMillis) {
                return true;
            }
        };
        harness.close();
        redis = unlocked;
        harness = new EnrollmentHarness(unlocked, 20, 10);
        harness.academicService.student(STUDENT, 9);
        harness.academicService.offering(8, "C1", "자바", 30, List.of(), "MON", "09:00", "10:30");
        harness.academicService.offering(9, "C2", "C++", 30, List.of(), "MON", "10:00", "11:30");
        unlocked.seats(8, 30);
        unlocked.seats(9, 30);
        unlocked.admit(STUDENT, System.currentTimeMillis() + 60_000);

        List<Status.Code> results = raceConflictingEnrolls();

        assertEquals(List.of(Status.Code.OK, Status.Code.OK), results);
        assertEquals(2, unlocked.studentOfferings(STUDENT).size());
    }

    private List<Status.Code> raceConflictingEnrolls() throws Exception {
        redis.readDelayMs = 150;
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Status.Code>> futures = new ArrayList<>();
        for (int offeringId : List.of(8, 9)) {
            futures.add(executor.submit(() -> {
                start.await();
                try {
                    harness.blocking(STUDENT).enroll(EnrollRequest.newBuilder().setOfferingId(offeringId).build());
                    return Status.Code.OK;
                } catch (StatusRuntimeException e) {
                    return e.getStatus().getCode();
                }
            }));
        }
        start.countDown();
        List<Status.Code> results = new ArrayList<>();
        for (Future<Status.Code> future : futures) {
            results.add(future.get());
        }
        executor.shutdown();
        redis.readDelayMs = 0;
        return results;
    }

    @Test
    void listMyEnrollmentsShowsCredits() {
        enroll(8);

        ListMyEnrollmentsResponse response = stub.listMyEnrollments(ListMyEnrollmentsRequest.getDefaultInstance());

        assertEquals(3, response.getTotalCredits());
        assertEquals(9, response.getMaxCredits());
        assertEquals(Map.of(8, 29), Map.of(response.getOfferings(0).getOfferingId(),
                response.getOfferings(0).getRemaining()));
    }
}
