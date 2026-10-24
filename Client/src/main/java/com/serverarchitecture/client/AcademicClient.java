package com.serverarchitecture.client;

import com.serverarchitecture.AcademicServiceGrpc;
import com.serverarchitecture.AddCourseRequest;
import com.serverarchitecture.AddStudentRequest;
import com.serverarchitecture.CourseInfo;
import com.serverarchitecture.DeleteCourseRequest;
import com.serverarchitecture.DeleteStudentRequest;
import com.serverarchitecture.ListCompletedCoursesRequest;
import com.serverarchitecture.ListCoursesByStudentRequest;
import com.serverarchitecture.ListCoursesRequest;
import com.serverarchitecture.ListStudentsByCourseRequest;
import com.serverarchitecture.ListStudentsRequest;
import com.serverarchitecture.StudentInfo;
import io.grpc.CallCredentials;
import io.grpc.Channel;

import java.util.List;
import java.util.concurrent.TimeUnit;

public class AcademicClient {
    static final long DEADLINE_SECONDS = 10;

    private final AcademicServiceGrpc.AcademicServiceBlockingStub stub;

    public AcademicClient(Channel channel, CallCredentials credentials) {
        this.stub = AcademicServiceGrpc.newBlockingStub(channel).withCallCredentials(credentials);
    }

    private AcademicServiceGrpc.AcademicServiceBlockingStub call() {
        return stub.withDeadlineAfter(DEADLINE_SECONDS, TimeUnit.SECONDS);
    }

    public List<StudentInfo> listStudents() {
        return call().listStudents(ListStudentsRequest.newBuilder().build()).getStudentsList();
    }

    public List<CourseInfo> listCourses() {
        return call().listCourses(ListCoursesRequest.newBuilder().build()).getCoursesList();
    }

    public List<CourseInfo> listCoursesByStudent(String studentId) {
        return call().listCoursesByStudent(ListCoursesByStudentRequest.newBuilder()
                .setStudentId(studentId)
                .build()).getCoursesList();
    }

    public List<StudentInfo> listStudentsByCourse(String courseId) {
        return call().listStudentsByCourse(ListStudentsByCourseRequest.newBuilder()
                .setCourseId(courseId)
                .build()).getStudentsList();
    }

    public List<CourseInfo> listCompletedCourses(String studentId) {
        return call().listCompletedCourses(ListCompletedCoursesRequest.newBuilder()
                .setStudentId(studentId)
                .build()).getCoursesList();
    }

    public StudentInfo addStudent(StudentInfo student) {
        return call().addStudent(AddStudentRequest.newBuilder().setStudent(student).build());
    }

    public String deleteStudent(String studentId) {
        return call().deleteStudent(DeleteStudentRequest.newBuilder()
                .setStudentId(studentId)
                .build()).getStudentId();
    }

    public CourseInfo addCourse(CourseInfo course) {
        return call().addCourse(AddCourseRequest.newBuilder().setCourse(course).build());
    }

    public String deleteCourse(String courseId) {
        return call().deleteCourse(DeleteCourseRequest.newBuilder()
                .setCourseId(courseId)
                .build()).getCourseId();
    }
}
