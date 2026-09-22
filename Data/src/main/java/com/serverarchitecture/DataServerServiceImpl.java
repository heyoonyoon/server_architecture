package com.serverarchitecture;


import io.grpc.stub.StreamObserver;

import java.io.IOException;

public class DataServerServiceImpl
        extends DataServerServiceGrpc.DataServerServiceImplBase {

    @Override
    public void getStudentListRepositoryRepository(
            GetStudentListRepositoryRequest request,
            StreamObserver<GetStudentListRepositoryResponse> responseObserver) {

        StudentList studentList;
        boolean success = false;
        try {
            studentList = new StudentList("Data/src/main/java/com/serverarchitecture/Students.txt");
            success = true;
        } catch (IOException e) {
            responseObserver.onError(io.grpc.Status.INTERNAL
                    .withDescription("Students.txt를 읽을 수 없습니다.")
                    .withCause(e)
                    .asRuntimeException());
            return;
        }

        GetStudentListRepositoryResponse.Builder builder =
                GetStudentListRepositoryResponse.newBuilder();

        for (Student student : studentList.getAllStudentRecords()) {
            StudentInfo info = StudentInfo.newBuilder()
                    .setStudentId(student.studentId)
                    .setName(student.getName())
                    .setDepartment(student.department)
                    .addAllCompletedCourseIds(student.getCompletedCourses())
                    .build();

            builder.addStudents(info);
        }

        GetStudentListRepositoryResponse response = builder
                .setSuccess(true)
                .build();
        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    @Override
    public void getCourseListRepository(
            GetCourseListRepositoryRequest request,
            StreamObserver<GetCourseListRepositoryResponse> responseObserver) {

        CourseList courseList;
        try {
            courseList = new CourseList("Data/src/main/java/com/serverarchitecture/Courses.txt");
        } catch (IOException e) {
            responseObserver.onError(io.grpc.Status.INTERNAL
                    .withDescription("Courses.txt를 읽을 수 없습니다.")
                    .withCause(e)
                    .asRuntimeException());
            return;
        }

        GetCourseListRepositoryResponse.Builder builder = GetCourseListRepositoryResponse.newBuilder();

        for (Course course : courseList.getAllCourseRecords()) {
            CourseInfo info = CourseInfo.newBuilder()
                    .setCourseId(course.courseId)
                    .setCourseName(course.getName())
                    .setInstructorName(course.getInstructorName())
                    .addAllPrerequisiteCourseIds(course.getPrerequisiteCourses())
                    .build();

            builder.addCourses(info);
        }

        GetCourseListRepositoryResponse response = builder
                .setSuccess(true)
                .build();
        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    @Override
    public void getEnrollmentListRepository(
            GetEnrollmentListRepositoryRequest request,
            StreamObserver<GetEnrollmentListRepositoryResponse> responseObserver) {

        EnrollmentList enrollmentList;
        try {
            enrollmentList = new EnrollmentList("Data/src/main/java/com/serverarchitecture/Enrollments.txt");
        } catch (IOException e) {
            responseObserver.onError(io.grpc.Status.INTERNAL
                    .withDescription("Enrollments.txt를 읽을 수 없습니다.")
                    .withCause(e)
                    .asRuntimeException());
            return;
        }

        GetEnrollmentListRepositoryResponse.Builder builder = GetEnrollmentListRepositoryResponse.newBuilder();

        for (Enrollment enrollment : enrollmentList.getAllEnrollmentRecords()) {
            EnrollmentInfo info = EnrollmentInfo.newBuilder()
                    .setStudentId(enrollment.getStudentId())
                    .setCourseId(enrollment.getCourseId())
                    .build();

            builder.addEnrollments(info);
        }

        GetEnrollmentListRepositoryResponse response = builder
                .setSuccess(true)
                .build();
        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }
}
