package com.serverarchitecture.client;

import com.serverarchitecture.AcademicServiceGrpc;
import com.serverarchitecture.AddStudentRequest;
import com.serverarchitecture.AuthServiceGrpc;
import com.serverarchitecture.CancelEnrollmentRequest;
import com.serverarchitecture.CancelEnrollmentResponse;
import com.serverarchitecture.CourseInfo;
import com.serverarchitecture.CourseList;
import com.serverarchitecture.DeleteStudentRequest;
import com.serverarchitecture.DeleteStudentResponse;
import com.serverarchitecture.EnrollRequest;
import com.serverarchitecture.EnrollResponse;
import com.serverarchitecture.EnrollmentServiceGrpc;
import com.serverarchitecture.EnterQueueRequest;
import com.serverarchitecture.GetQueueStatusRequest;
import com.serverarchitecture.LeaveQueueRequest;
import com.serverarchitecture.LeaveQueueResponse;
import com.serverarchitecture.ListCompletedCoursesRequest;
import com.serverarchitecture.ListCoursesRequest;
import com.serverarchitecture.ListMyEnrollmentsRequest;
import com.serverarchitecture.ListMyEnrollmentsResponse;
import com.serverarchitecture.ListOfferingsRequest;
import com.serverarchitecture.ListOfferingsResponse;
import com.serverarchitecture.ListStudentsRequest;
import com.serverarchitecture.LoginRequest;
import com.serverarchitecture.LoginResponse;
import com.serverarchitecture.OfferingInfo;
import com.serverarchitecture.OfferingTime;
import com.serverarchitecture.QueueState;
import com.serverarchitecture.QueueStatusResponse;
import com.serverarchitecture.Role;
import com.serverarchitecture.StudentInfo;
import com.serverarchitecture.StudentList;
import com.serverarchitecture.WatchQueueRequest;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

class FakeServices {

    static class TokenRecorder implements ServerInterceptor {
        final List<String> calls = new CopyOnWriteArrayList<String>();

        @Override
        public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
                ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
            String method = call.getMethodDescriptor().getFullMethodName();
            String authorization = headers.get(TokenCredentials.AUTHORIZATION);
            calls.add(method + " " + authorization);
            if (!method.startsWith("serverarchitecture.AuthService/") && authorization == null) {
                call.close(Status.UNAUTHENTICATED.withDescription("로그인이 필요합니다."), new Metadata());
                return new ServerCall.Listener<ReqT>() {
                };
            }
            return next.startCall(call, headers);
        }
    }

    static class Auth extends AuthServiceGrpc.AuthServiceImplBase {
        @Override
        public void login(LoginRequest request, StreamObserver<LoginResponse> responseObserver) {
            String id = request.getUserId();
            if ((id.equals("s1") || id.equals("p1")) && request.getPassword().equals(id)) {
                responseObserver.onNext(LoginResponse.newBuilder()
                        .setToken("token-" + id)
                        .setUserId(id)
                        .setName(id.equals("s1") ? "김학생" : "이교수")
                        .setRole(id.equals("s1") ? Role.STUDENT : Role.PROFESSOR)
                        .build());
                responseObserver.onCompleted();
                return;
            }
            responseObserver.onError(Status.UNAUTHENTICATED
                    .withDescription("아이디 또는 비밀번호가 올바르지 않습니다.")
                    .asRuntimeException());
        }
    }

    static class Academic extends AcademicServiceGrpc.AcademicServiceImplBase {
        final List<StudentInfo> added = new CopyOnWriteArrayList<StudentInfo>();

        static final List<CourseInfo> COURSES = List.of(
                CourseInfo.newBuilder().setCourseId("CS101").setCourseName("자료구조")
                        .setInstructorName("이교수").setCredits(3).build(),
                CourseInfo.newBuilder().setCourseId("CS201").setCourseName("운영체제")
                        .setInstructorName("박교수").setCredits(3).addPrerequisiteCourseIds("CS101").build());

        @Override
        public void listStudents(ListStudentsRequest request, StreamObserver<StudentList> responseObserver) {
            responseObserver.onNext(StudentList.newBuilder()
                    .addStudents(StudentInfo.newBuilder().setStudentId("s1").setName("김학생")
                            .setDepartment("컴퓨터공학과").setMaxCredits(18).addCompletedCourseIds("CS101"))
                    .build());
            responseObserver.onCompleted();
        }

        @Override
        public void listCourses(ListCoursesRequest request, StreamObserver<CourseList> responseObserver) {
            responseObserver.onNext(CourseList.newBuilder().addAllCourses(COURSES).build());
            responseObserver.onCompleted();
        }

        @Override
        public void listCompletedCourses(ListCompletedCoursesRequest request,
                                         StreamObserver<CourseList> responseObserver) {
            responseObserver.onNext(CourseList.newBuilder().addCourses(COURSES.get(0)).build());
            responseObserver.onCompleted();
        }

        @Override
        public void addStudent(AddStudentRequest request, StreamObserver<StudentInfo> responseObserver) {
            added.add(request.getStudent());
            responseObserver.onNext(request.getStudent().toBuilder().setMaxCredits(18).build());
            responseObserver.onCompleted();
        }

        @Override
        public void deleteStudent(DeleteStudentRequest request,
                                  StreamObserver<DeleteStudentResponse> responseObserver) {
            responseObserver.onError(Status.NOT_FOUND
                    .withDescription("존재하지 않는 학생입니다.")
                    .asRuntimeException());
        }
    }

    static class Enrollment extends EnrollmentServiceGrpc.EnrollmentServiceImplBase {
        final AtomicInteger leaveCount = new AtomicInteger();
        final List<Integer> enrolled = new CopyOnWriteArrayList<Integer>();
        final Map<Integer, Integer> remaining = new LinkedHashMap<Integer, Integer>(Map.of(8, 2, 9, 0));
        volatile QueueState state = QueueState.NOT_IN_QUEUE;
        volatile boolean expireOnEnroll;

        private QueueStatusResponse status(QueueState state, int position) {
            return QueueStatusResponse.newBuilder()
                    .setState(state)
                    .setPosition(position)
                    .setPassRemainingSeconds(state == QueueState.ADMITTED ? 300 : 0)
                    .build();
        }

        private boolean requirePass(StreamObserver<?> responseObserver) {
            if (state == QueueState.ADMITTED) {
                return true;
            }
            responseObserver.onError(Status.FAILED_PRECONDITION
                    .withDescription("입장권이 없거나 만료되었습니다. 대기열에 다시 들어가세요.")
                    .asRuntimeException());
            return false;
        }

        private OfferingInfo offering(int id) {
            return OfferingInfo.newBuilder()
                    .setOfferingId(id)
                    .setCourseId(id == 8 ? "CS101" : "CS201")
                    .setCourseName(id == 8 ? "자료구조" : "운영체제")
                    .setSection("01")
                    .setProfessorName("이교수")
                    .setCredits(3)
                    .setRoom("Y5401")
                    .setCapacity(30)
                    .setRemaining(remaining.get(id))
                    .addTimes(OfferingTime.newBuilder().setDayOfWeek("MON").setStartTime("09:00").setEndTime("10:30"))
                    .build();
        }

        @Override
        public void getQueueStatus(GetQueueStatusRequest request, StreamObserver<QueueStatusResponse> responseObserver) {
            responseObserver.onNext(status(state, state == QueueState.WAITING ? 3 : 0));
            responseObserver.onCompleted();
        }

        @Override
        public void enterQueue(EnterQueueRequest request, StreamObserver<QueueStatusResponse> responseObserver) {
            if (state == QueueState.NOT_IN_QUEUE) {
                state = QueueState.WAITING;
            }
            responseObserver.onNext(status(state, 3));
            responseObserver.onCompleted();
        }

        @Override
        public void watchQueue(WatchQueueRequest request, StreamObserver<QueueStatusResponse> responseObserver) {
            responseObserver.onNext(status(QueueState.WAITING, 3));
            responseObserver.onNext(status(QueueState.WAITING, 3));
            responseObserver.onNext(status(QueueState.WAITING, 2));
            responseObserver.onNext(status(QueueState.WAITING, 1));
            state = QueueState.ADMITTED;
            responseObserver.onNext(status(QueueState.ADMITTED, 0));
            responseObserver.onCompleted();
        }

        @Override
        public void leaveQueue(LeaveQueueRequest request, StreamObserver<LeaveQueueResponse> responseObserver) {
            leaveCount.incrementAndGet();
            state = QueueState.NOT_IN_QUEUE;
            responseObserver.onNext(LeaveQueueResponse.newBuilder().setReleased(true).build());
            responseObserver.onCompleted();
        }

        @Override
        public void listOfferings(ListOfferingsRequest request, StreamObserver<ListOfferingsResponse> responseObserver) {
            if (!requirePass(responseObserver)) {
                return;
            }
            responseObserver.onNext(ListOfferingsResponse.newBuilder()
                    .addOfferings(offering(8))
                    .addOfferings(offering(9))
                    .build());
            responseObserver.onCompleted();
        }

        @Override
        public void enroll(EnrollRequest request, StreamObserver<EnrollResponse> responseObserver) {
            if (expireOnEnroll) {
                state = QueueState.NOT_IN_QUEUE;
            }
            if (!requirePass(responseObserver)) {
                return;
            }
            int id = request.getOfferingId();
            if (!remaining.containsKey(id)) {
                responseObserver.onError(Status.NOT_FOUND.withDescription("존재하지 않는 강의입니다.").asRuntimeException());
                return;
            }
            if (remaining.get(id) == 0) {
                responseObserver.onError(Status.FAILED_PRECONDITION.withDescription("정원이 마감되었습니다.").asRuntimeException());
                return;
            }
            remaining.put(id, remaining.get(id) - 1);
            enrolled.add(id);
            responseObserver.onNext(EnrollResponse.newBuilder()
                    .setMessage("신청이 완료되었습니다.")
                    .setOffering(offering(id))
                    .build());
            responseObserver.onCompleted();
        }

        @Override
        public void cancelEnrollment(CancelEnrollmentRequest request,
                                     StreamObserver<CancelEnrollmentResponse> responseObserver) {
            if (!requirePass(responseObserver)) {
                return;
            }
            int id = request.getOfferingId();
            if (!enrolled.remove(Integer.valueOf(id))) {
                responseObserver.onError(Status.FAILED_PRECONDITION.withDescription("신청 내역이 없습니다.").asRuntimeException());
                return;
            }
            remaining.put(id, remaining.get(id) + 1);
            responseObserver.onNext(CancelEnrollmentResponse.newBuilder()
                    .setMessage("신청을 취소했습니다.")
                    .setOfferingId(id)
                    .build());
            responseObserver.onCompleted();
        }

        @Override
        public void listMyEnrollments(ListMyEnrollmentsRequest request,
                                      StreamObserver<ListMyEnrollmentsResponse> responseObserver) {
            if (!requirePass(responseObserver)) {
                return;
            }
            List<OfferingInfo> mine = new ArrayList<OfferingInfo>();
            for (Integer id : enrolled) {
                mine.add(offering(id));
            }
            responseObserver.onNext(ListMyEnrollmentsResponse.newBuilder()
                    .addAllOfferings(mine)
                    .setTotalCredits(mine.size() * 3)
                    .setMaxCredits(18)
                    .build());
            responseObserver.onCompleted();
        }
    }
}
