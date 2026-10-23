package com.serverarchitecture.enrollment;

import com.serverarchitecture.AuthInterceptor;
import com.serverarchitecture.CancelEnrollmentRequest;
import com.serverarchitecture.CancelEnrollmentResponse;
import com.serverarchitecture.CustomException;
import com.serverarchitecture.EnrollRequest;
import com.serverarchitecture.EnrollResponse;
import com.serverarchitecture.EnrollmentEventType;
import com.serverarchitecture.EnrollmentServiceGrpc;
import com.serverarchitecture.EnterQueueRequest;
import com.serverarchitecture.GetQueueStatusRequest;
import com.serverarchitecture.GetScheduleRequest;
import com.serverarchitecture.GetScheduleResponse;
import com.serverarchitecture.LeaveQueueRequest;
import com.serverarchitecture.LeaveQueueResponse;
import com.serverarchitecture.ListMyEnrollmentsRequest;
import com.serverarchitecture.ListMyEnrollmentsResponse;
import com.serverarchitecture.ListOfferingsRequest;
import com.serverarchitecture.ListOfferingsResponse;
import com.serverarchitecture.NullDataException;
import com.serverarchitecture.OfferingInfo;
import com.serverarchitecture.OfferingTime;
import com.serverarchitecture.QueueState;
import com.serverarchitecture.QueueStatusResponse;
import com.serverarchitecture.StudentInfo;
import com.serverarchitecture.WatchQueueRequest;
import com.serverarchitecture.redis.RedisException;
import com.serverarchitecture.redis.RedisPoolExhaustedException;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class EnrollmentServiceImpl extends EnrollmentServiceGrpc.EnrollmentServiceImplBase {
    private static final Logger log = LoggerFactory.getLogger(EnrollmentServiceImpl.class);
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter OPEN_TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final AcademicClient academic;
    private final OfferingCatalog catalog;
    private final EnrollmentRedis redis;
    private final EnrollmentEventPublisher publisher;
    private final QueueWatcher watcher;
    private final StudentLock studentLock;

    public EnrollmentServiceImpl(AcademicClient academic, OfferingCatalog catalog, EnrollmentRedis redis,
                                 EnrollmentEventPublisher publisher, QueueWatcher watcher, StudentLock studentLock) {
        this.academic = academic;
        this.catalog = catalog;
        this.redis = redis;
        this.publisher = publisher;
        this.watcher = watcher;
        this.studentLock = studentLock;
    }

    @Override
    public void enterQueue(EnterQueueRequest request, StreamObserver<QueueStatusResponse> responseObserver) {
        respond(responseObserver, () -> {
            String studentId = currentStudentId();
            long now = System.currentTimeMillis();
            requireOpen(now);
            if (redis.passExpiresAt(studentId, now) == null) {
                redis.enterQueue(studentId, now);
            }
            return queueStatus(studentId, now);
        });
    }

    @Override
    public void getQueueStatus(GetQueueStatusRequest request, StreamObserver<QueueStatusResponse> responseObserver) {
        respond(responseObserver, () -> queueStatus(currentStudentId(), System.currentTimeMillis()));
    }

    @Override
    public void watchQueue(WatchQueueRequest request, StreamObserver<QueueStatusResponse> responseObserver) {
        String studentId = currentStudentId();
        watcher.watch((ServerCallStreamObserver<QueueStatusResponse>) responseObserver,
                () -> queueStatus(studentId, System.currentTimeMillis()),
                this::toStatusException);
    }

    @Override
    public void leaveQueue(LeaveQueueRequest request, StreamObserver<LeaveQueueResponse> responseObserver) {
        respond(responseObserver, () -> LeaveQueueResponse.newBuilder()
                .setReleased(redis.leaveQueue(currentStudentId()))
                .build());
    }

    @Override
    public void getSchedule(GetScheduleRequest request, StreamObserver<GetScheduleResponse> responseObserver) {
        respond(responseObserver, () -> {
            Long openAt = redis.openAt();
            return GetScheduleResponse.newBuilder()
                    .setOpenAtMs(openAt == null ? 0 : openAt)
                    .setServerNowMs(System.currentTimeMillis())
                    .build();
        });
    }

    @Override
    public void listOfferings(ListOfferingsRequest request, StreamObserver<ListOfferingsResponse> responseObserver) {
        respond(responseObserver, () -> {
            requirePass(currentStudentId());
            Map<Integer, Integer> seats = redis.remainingSeats();
            ListOfferingsResponse.Builder builder = ListOfferingsResponse.newBuilder();
            for (Offering offering : catalog.offerings().values()) {
                builder.addOfferings(toInfo(offering, seats));
            }
            return builder.build();
        });
    }

    @Override
    public void enroll(EnrollRequest request, StreamObserver<EnrollResponse> responseObserver) {
        respond(responseObserver, () -> {
            String studentId = currentStudentId();
            requirePass(studentId);
            Offering offering = enroll(studentId, request.getOfferingId());
            return EnrollResponse.newBuilder()
                    .setMessage("신청이 완료되었습니다.")
                    .setOffering(toInfo(offering, redis.remainingSeats()))
                    .build();
        });
    }

    @Override
    public void cancelEnrollment(CancelEnrollmentRequest request,
                                 StreamObserver<CancelEnrollmentResponse> responseObserver) {
        respond(responseObserver, () -> {
            String studentId = currentStudentId();
            requirePass(studentId);
            cancel(studentId, request.getOfferingId());
            return CancelEnrollmentResponse.newBuilder()
                    .setMessage("신청을 취소했습니다.")
                    .setOfferingId(request.getOfferingId())
                    .build();
        });
    }

    @Override
    public void listMyEnrollments(ListMyEnrollmentsRequest request,
                                  StreamObserver<ListMyEnrollmentsResponse> responseObserver) {
        respond(responseObserver, () -> {
            String studentId = currentStudentId();
            requirePass(studentId);
            Map<Integer, Offering> offerings = catalog.offerings();
            List<Offering> enrolled = enrolledOfferings(offerings, redis.studentOfferings(studentId));
            Map<Integer, Integer> seats = redis.remainingSeats();

            ListMyEnrollmentsResponse.Builder builder = ListMyEnrollmentsResponse.newBuilder();
            for (Offering offering : enrolled) {
                builder.addOfferings(toInfo(offering, seats));
            }
            return builder
                    .setTotalCredits(EnrollmentRules.totalCredits(enrolled))
                    .setMaxCredits(academic.getStudent(studentId).getMaxCredits())
                    .build();
        });
    }

    private Offering enroll(String studentId, int offeringId) {
        StudentInfo student = academic.getStudent(studentId);
        Map<Integer, Offering> offerings = catalog.offerings();
        Offering offering = offerings.get(offeringId);
        if (offering == null) {
            throw new NullDataException("존재하지 않는 강의입니다.");
        }

        List<String> missing = EnrollmentRules.missingPrerequisites(offering,
                new HashSet<String>(student.getCompletedCourseIdsList()));
        if (!missing.isEmpty()) {
            throw new CustomException(Status.Code.ABORTED,
                    "선수과목(" + String.join(", ", missing) + ")을 이수하지 않아 수강신청할 수 없습니다.");
        }

        return studentLock.call(studentId, () -> enrollLocked(studentId, student, offerings, offering));
    }

    private Offering enrollLocked(String studentId, StudentInfo student, Map<Integer, Offering> offerings,
                                  Offering offering) {
        int offeringId = offering.offeringId();
        Set<Integer> enrolledIds = redis.studentOfferings(studentId);
        if (enrolledIds.contains(offeringId)) {
            throw new CustomException(Status.Code.ALREADY_EXISTS, "이미 신청한 강의입니다.");
        }
        List<Offering> enrolled = enrolledOfferings(offerings, enrolledIds);
        Offering conflict = EnrollmentRules.findTimeConflict(offering, enrolled);
        if (conflict != null) {
            throw new CustomException(Status.Code.ABORTED,
                    "신청한 강의(" + conflict.courseName() + ")와 시간이 겹쳐 수강신청할 수 없습니다.");
        }
        if (EnrollmentRules.hasSameCourse(offering, enrolled)) {
            throw new CustomException(Status.Code.ALREADY_EXISTS,
                    "같은 과목(" + offering.courseName() + ")의 다른 분반을 이미 신청했습니다.");
        }
        int maxCredits = student.getMaxCredits();
        if (EnrollmentRules.exceedsCredits(offering, enrolled, maxCredits)) {
            throw new CustomException(Status.Code.OUT_OF_RANGE,
                    "신청 가능 학점(" + maxCredits + "학점)을 초과합니다. 현재 "
                            + EnrollmentRules.totalCredits(enrolled) + "학점을 신청했습니다.");
        }

        long result = redis.enroll(studentId, offeringId);
        if (result == -1) {
            throw new CustomException(Status.Code.ALREADY_EXISTS, "이미 신청한 강의입니다.");
        }
        if (result == -2) {
            throw new CustomException(Status.Code.RESOURCE_EXHAUSTED, "정원이 마감되었습니다.");
        }
        if (result == -3) {
            throw new CustomException(Status.Code.UNAVAILABLE, "수강신청 준비 중입니다. 잠시 후 다시 시도하세요.");
        }

        try {
            publisher.publish(EnrollmentEventType.ENROLLED, studentId, offeringId);
        } catch (Exception e) {
            log.error("수강신청 이벤트 발행 실패 student={} offering={}", studentId, offeringId, e);
            redis.cancel(studentId, offeringId);
            throw new CustomException(Status.Code.UNAVAILABLE, "신청 처리 중 오류가 발생했습니다. 다시 시도하세요.");
        }
        return offering;
    }

    private void cancel(String studentId, int offeringId) {
        studentLock.call(studentId, () -> {
            if (!redis.studentOfferings(studentId).contains(offeringId)) {
                throw new NullDataException("신청 내역이 없습니다.");
            }
            try {
                publisher.publish(EnrollmentEventType.CANCELLED, studentId, offeringId);
            } catch (Exception e) {
                log.error("수강취소 이벤트 발행 실패 student={} offering={}", studentId, offeringId, e);
                throw new CustomException(Status.Code.UNAVAILABLE, "취소 처리 중 오류가 발생했습니다. 다시 시도하세요.");
            }
            if (redis.cancel(studentId, offeringId) == 0) {
                log.warn("수강취소 좌석 반환 대상 없음 student={} offering={}", studentId, offeringId);
            }
            return null;
        });
    }

    private QueueStatusResponse queueStatus(String studentId, long now) {
        QueueStatusResponse.Builder builder = QueueStatusResponse.newBuilder()
                .setNextPollMs(EnrollmentConfig.POLL_INTERVAL_MS);

        Long expiresAt = redis.passExpiresAt(studentId, now);
        if (expiresAt != null) {
            return builder.setState(QueueState.ADMITTED)
                    .setPassRemainingSeconds((int) ((expiresAt - now + 999) / 1000))
                    .build();
        }

        Long rank = redis.queueRank(studentId);
        if (rank != null) {
            redis.markSeen(studentId, now);
            return builder.setState(QueueState.WAITING)
                    .setPosition((int) (rank + 1))
                    .build();
        }
        return builder.setState(QueueState.NOT_IN_QUEUE).build();
    }

    private void requireOpen(long now) {
        Long openAt = redis.openAt();
        if (openAt != null && now < openAt) {
            String time = OPEN_TIME_FORMAT.format(Instant.ofEpochMilli(openAt).atZone(EnrollmentConfig.ZONE));
            throw new CustomException(Status.Code.FAILED_PRECONDITION, "수강신청 진입은 " + time + "부터 가능합니다.");
        }
    }

    private void requirePass(String studentId) {
        if (!redis.extendPass(studentId, System.currentTimeMillis())) {
            throw new CustomException(Status.Code.FAILED_PRECONDITION,
                    "입장권이 없거나 만료되었습니다. 대기열에 다시 들어가세요.");
        }
    }

    private List<Offering> enrolledOfferings(Map<Integer, Offering> offerings, Set<Integer> offeringIds) {
        List<Offering> enrolled = new ArrayList<Offering>();
        for (Offering offering : offerings.values()) {
            if (offeringIds.contains(offering.offeringId())) {
                enrolled.add(offering);
            }
        }
        return enrolled;
    }

    private OfferingInfo toInfo(Offering offering, Map<Integer, Integer> seats) {
        OfferingInfo.Builder builder = OfferingInfo.newBuilder()
                .setOfferingId(offering.offeringId())
                .setCourseId(offering.courseId())
                .setCourseName(offering.courseName())
                .setCredits(offering.credits())
                .setSemester(offering.semester())
                .setSection(offering.section())
                .setProfessorId(offering.professorId())
                .setProfessorName(offering.professorName())
                .setRoom(offering.room() == null ? "" : offering.room())
                .setCapacity(offering.capacity())
                .setRemaining(seats.getOrDefault(offering.offeringId(), offering.capacity() - offering.enrolledCount()))
                .addAllPrerequisiteCourseIds(offering.prerequisiteCourseIds());
        for (TimeSlot time : offering.times()) {
            builder.addTimes(OfferingTime.newBuilder()
                    .setDayOfWeek(time.dayOfWeek())
                    .setStartTime(time.startTime().format(TIME_FORMAT))
                    .setEndTime(time.endTime().format(TIME_FORMAT)));
        }
        return builder.build();
    }

    private String currentStudentId() {
        return AuthInterceptor.USER.get().userId();
    }

    private <T> void respond(StreamObserver<T> responseObserver, Action<T> action) {
        T response;
        try {
            response = action.run();
        } catch (RuntimeException e) {
            responseObserver.onError(toStatusException(e));
            return;
        }
        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    StatusRuntimeException toStatusException(RuntimeException e) {
        if (e instanceof StatusRuntimeException status) {
            return status;
        }
        if (e instanceof CustomException custom) {
            return custom.toStatus().asRuntimeException();
        }
        if (e instanceof RedisPoolExhaustedException exhausted) {
            return busy(exhausted);
        }
        if (e instanceof RedisException) {
            log.warn("Redis 오류: {}", e.getMessage());
            return Status.UNAVAILABLE
                    .withDescription("수강신청 서버에 연결할 수 없습니다.")
                    .withCause(e)
                    .asRuntimeException();
        }
        log.error("처리하지 못한 오류", e);
        return Status.INTERNAL
                .withDescription("서버 내부 오류가 발생했습니다.")
                .withCause(e)
                .asRuntimeException();
    }

    private StatusRuntimeException busy(Exception e) {
        log.warn("연결 풀 대기 시간 초과: {}", e.getMessage());
        return Status.UNAVAILABLE
                .withDescription("요청이 많아 처리하지 못했습니다. 잠시 후 다시 시도하세요.")
                .withCause(e)
                .asRuntimeException();
    }

    private interface Action<T> {
        T run();
    }
}
