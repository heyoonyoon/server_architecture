package com.serverarchitecture;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.exceptions.JedisException;

import java.sql.SQLException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class EnrollmentServiceImpl extends EnrollmentServiceGrpc.EnrollmentServiceImplBase {
    private static final Logger log = LoggerFactory.getLogger(EnrollmentServiceImpl.class);
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter OPEN_TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final OfferingRepository offeringRepository;
    private final EnrollmentRedis redis;
    private final EnrollmentEventPublisher publisher;

    public EnrollmentServiceImpl(OfferingRepository offeringRepository, EnrollmentRedis redis,
                                 EnrollmentEventPublisher publisher) {
        this.offeringRepository = offeringRepository;
        this.redis = redis;
        this.publisher = publisher;
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
            for (Offering offering : offeringRepository.findBySemester(EnrollmentConfig.SEMESTER).values()) {
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
            String message = enroll(studentId, request.getOfferingId());
            return EnrollResponse.newBuilder()
                    .setSuccess(message == null)
                    .setMessage(message == null ? "신청이 완료되었습니다." : message)
                    .build();
        });
    }

    @Override
    public void cancelEnrollment(CancelEnrollmentRequest request,
                                 StreamObserver<CancelEnrollmentResponse> responseObserver) {
        respond(responseObserver, () -> {
            String studentId = currentStudentId();
            requirePass(studentId);
            String message = cancel(studentId, request.getOfferingId());
            return CancelEnrollmentResponse.newBuilder()
                    .setSuccess(message == null)
                    .setMessage(message == null ? "신청을 취소했습니다." : message)
                    .build();
        });
    }

    @Override
    public void listMyEnrollments(ListMyEnrollmentsRequest request,
                                  StreamObserver<ListMyEnrollmentsResponse> responseObserver) {
        respond(responseObserver, () -> {
            String studentId = currentStudentId();
            requirePass(studentId);
            Map<Integer, Offering> offerings = offeringRepository.findBySemester(EnrollmentConfig.SEMESTER);
            List<Offering> enrolled = enrolledOfferings(offerings, redis.studentOfferings(studentId));
            Map<Integer, Integer> seats = redis.remainingSeats();

            ListMyEnrollmentsResponse.Builder builder = ListMyEnrollmentsResponse.newBuilder();
            for (Offering offering : enrolled) {
                builder.addOfferings(toInfo(offering, seats));
            }
            return builder
                    .setTotalCredits(EnrollmentRules.totalCredits(enrolled))
                    .setMaxCredits(offeringRepository.findMaxCredits(studentId))
                    .build();
        });
    }

    private String enroll(String studentId, int offeringId) throws SQLException {
        Map<Integer, Offering> offerings = offeringRepository.findBySemester(EnrollmentConfig.SEMESTER);
        Offering offering = offerings.get(offeringId);
        if (offering == null) {
            return "존재하지 않는 강의입니다.";
        }

        List<String> missing = EnrollmentRules.missingPrerequisites(offering,
                offeringRepository.findCompletedCourseIds(studentId));
        if (!missing.isEmpty()) {
            return "선수과목(" + String.join(", ", missing) + ")을 이수하지 않았습니다.";
        }

        List<Offering> enrolled = enrolledOfferings(offerings, redis.studentOfferings(studentId));
        Offering conflict = EnrollmentRules.findTimeConflict(offering, enrolled);
        if (conflict != null) {
            return "신청한 강의(" + conflict.courseName() + ")와 시간이 겹칩니다.";
        }
        if (EnrollmentRules.hasSameCourse(offering, enrolled)) {
            return "이미 신청한 과목입니다.";
        }
        int maxCredits = offeringRepository.findMaxCredits(studentId);
        if (EnrollmentRules.exceedsCredits(offering, enrolled, maxCredits)) {
            return "신청 가능 학점(" + maxCredits + ")을 초과합니다.";
        }

        long result = redis.enroll(studentId, offeringId);
        if (result == -1) {
            return "이미 신청한 강의입니다.";
        }
        if (result == -2) {
            return "정원이 마감되었습니다.";
        }
        if (result == -3) {
            return "수강신청 준비 중입니다. 잠시 후 다시 시도하세요.";
        }

        try {
            publisher.publish(EnrollmentEventType.ENROLLED, studentId, offeringId);
        } catch (Exception e) {
            log.error("수강신청 이벤트 발행 실패 student={} offering={}", studentId, offeringId, e);
            redis.cancel(studentId, offeringId);
            return "신청 처리 중 오류가 발생했습니다. 다시 시도하세요.";
        }
        return null;
    }

    private String cancel(String studentId, int offeringId) {
        if (redis.cancel(studentId, offeringId) == 0) {
            return "신청 내역이 없습니다.";
        }

        try {
            publisher.publish(EnrollmentEventType.CANCELLED, studentId, offeringId);
        } catch (Exception e) {
            log.error("수강취소 이벤트 발행 실패 student={} offering={}", studentId, offeringId, e);
            redis.enroll(studentId, offeringId);
            return "신청 처리 중 오류가 발생했습니다. 다시 시도하세요.";
        }
        return null;
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
            throw Status.FAILED_PRECONDITION
                    .withDescription("수강신청 진입은 " + time + "부터 가능합니다.")
                    .asRuntimeException();
        }
    }

    private void requirePass(String studentId) {
        if (!redis.extendPass(studentId, System.currentTimeMillis())) {
            throw Status.FAILED_PRECONDITION
                    .withDescription("입장권이 없거나 만료되었습니다. 대기열에 다시 들어가세요.")
                    .asRuntimeException();
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
        } catch (StatusRuntimeException e) {
            responseObserver.onError(e);
            return;
        } catch (SQLException e) {
            responseObserver.onError(Status.INTERNAL
                    .withDescription("데이터베이스 오류가 발생했습니다.")
                    .withCause(e)
                    .asRuntimeException());
            return;
        } catch (JedisException e) {
            responseObserver.onError(Status.UNAVAILABLE
                    .withDescription("수강신청 서버에 연결할 수 없습니다.")
                    .withCause(e)
                    .asRuntimeException());
            return;
        }
        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    private interface Action<T> {
        T run() throws SQLException;
    }
}
