package com.serverarchitecture;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.StreamObserver;

public class ServerClientServiceImpl
        extends ServerClientServiceGrpc.ServerClientServiceImplBase {


    ManagedChannel channel = ManagedChannelBuilder.forAddress("localhost", 8080).usePlaintext().build();

    DataServerServiceGrpc.DataServerServiceBlockingStub stub
            = DataServerServiceGrpc.newBlockingStub(channel);

    GetStudentListRepositoryRequest studentListRequest = GetStudentListRepositoryRequest.newBuilder()
            .build();
    GetCourseListRepositoryRequest courseListRequest = GetCourseListRepositoryRequest.newBuilder()
            .build();


    GetStudentListRepositoryResponse studentList = stub.getStudentListRepositoryRepository(studentListRequest);
    GetCourseListRepositoryResponse courseList = stub.getCourseListRepository(courseListRequest);


    @Override
    public void getStudentListController(
            GetStudentListControllerRequest request,
            StreamObserver<GetStudentListControllerResponse> responseObserver) {


        GetStudentListControllerResponse.Builder builder =
                GetStudentListControllerResponse.newBuilder();

        for (StudentInfo student : studentList.getStudentsList()) {
            StudentInfo info = StudentInfo.newBuilder()
                    .setStudentId(student.getStudentId())
                    .setName(student.getName())
                    .setDepartment(student.getDepartment())
                    .addAllCompletedCourseIds(student.getCompletedCourseIdsList())
                    .build();

            builder.addStudents(info);
        }

        GetStudentListControllerResponse response = builder
                .setSuccess(true)
                .build();
        responseObserver.onNext(response);
        responseObserver.onCompleted();


    }

    @Override
    public void getCourseListController(
            GetCourseListControllerRequest request,
            StreamObserver<GetCourseListControllerResponse> responseObserver) {

        GetCourseListControllerResponse.Builder builder =
                GetCourseListControllerResponse.newBuilder();

        for (CourseInfo course : courseList.getCoursesList()) {
            CourseInfo info = CourseInfo.newBuilder()
                    .setCourseId(course.getCourseId())
                    .setCourseName(course.getCourseName())
                    .setInstructorName(course.getInstructorName())
                    .addAllPrerequisiteCourseIds(course.getPrerequisiteCourseIdsList())
                    .build();

            builder.addCourses(info);
        }

        GetCourseListControllerResponse response = builder
                .setSuccess(true)
                .build();
        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }
}
