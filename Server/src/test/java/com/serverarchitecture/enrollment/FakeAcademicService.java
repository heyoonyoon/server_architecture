package com.serverarchitecture.enrollment;

import com.serverarchitecture.AcademicServiceGrpc;
import com.serverarchitecture.Catalog;
import com.serverarchitecture.GetCatalogRequest;
import com.serverarchitecture.GetStudentRequest;
import com.serverarchitecture.OfferingInfo;
import com.serverarchitecture.OfferingTime;
import com.serverarchitecture.StudentInfo;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

class FakeAcademicService extends AcademicServiceGrpc.AcademicServiceImplBase {
    final Map<String, StudentInfo> students = new ConcurrentHashMap<>();
    final List<OfferingInfo> offerings = new CopyOnWriteArrayList<>();
    final AtomicInteger studentCalls = new AtomicInteger();
    final AtomicInteger catalogCalls = new AtomicInteger();
    final AtomicInteger catalogFailures = new AtomicInteger();

    void student(String studentId, int maxCredits, String... completed) {
        students.put(studentId, StudentInfo.newBuilder()
                .setStudentId(studentId)
                .setName("학생" + studentId)
                .setMaxCredits(maxCredits)
                .addAllCompletedCourseIds(List.of(completed))
                .build());
    }

    void offering(int id, String courseId, String courseName, int capacity, List<String> prerequisites,
                  String day, String start, String end) {
        offerings.add(OfferingInfo.newBuilder()
                .setOfferingId(id)
                .setCourseId(courseId)
                .setCourseName(courseName)
                .setCredits(3)
                .setSemester("2026-2")
                .setSection("01")
                .setProfessorId("P001")
                .setProfessorName("교수")
                .setRoom("Y5401")
                .setCapacity(capacity)
                .setRemaining(capacity)
                .addTimes(OfferingTime.newBuilder().setDayOfWeek(day).setStartTime(start).setEndTime(end))
                .addAllPrerequisiteCourseIds(prerequisites)
                .build());
    }

    @Override
    public void getStudent(GetStudentRequest request, StreamObserver<StudentInfo> responseObserver) {
        studentCalls.incrementAndGet();
        StudentInfo student = students.get(request.getStudentId());
        if (student == null) {
            responseObserver.onError(Status.NOT_FOUND
                    .withDescription("학생(" + request.getStudentId() + ")을 찾을 수 없습니다.")
                    .asRuntimeException());
            return;
        }
        responseObserver.onNext(student);
        responseObserver.onCompleted();
    }

    @Override
    public void getCatalog(GetCatalogRequest request, StreamObserver<Catalog> responseObserver) {
        catalogCalls.incrementAndGet();
        if (catalogFailures.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
            responseObserver.onError(Status.UNAVAILABLE.withDescription("academic not ready").asRuntimeException());
            return;
        }
        responseObserver.onNext(Catalog.newBuilder().addAllOfferings(offerings).build());
        responseObserver.onCompleted();
    }
}
