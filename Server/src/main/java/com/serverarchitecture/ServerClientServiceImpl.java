package com.serverarchitecture;

import io.grpc.stub.StreamObserver;

import java.sql.SQLException;
import java.util.ArrayList;

public class ServerClientServiceImpl
        extends ServerClientServiceGrpc.ServerClientServiceImplBase {

    private static final String DUMMY_PASSWORD_HASH = PasswordHasher.hash("dummy");

    private final StudentRepository studentRepository;
    private final CourseRepository courseRepository;
    private final UserRepository userRepository;
    private final JwtProvider jwtProvider;
    private final OfferingCatalog catalog;

    public ServerClientServiceImpl(StudentRepository studentRepository, CourseRepository courseRepository,
                                   UserRepository userRepository, JwtProvider jwtProvider,
                                   OfferingCatalog catalog) {
        this.studentRepository = studentRepository;
        this.courseRepository = courseRepository;
        this.userRepository = userRepository;
        this.jwtProvider = jwtProvider;
        this.catalog = catalog;
    }

    @Override
    public void loginController(
            LoginControllerRequest request,
            StreamObserver<LoginControllerResponse> responseObserver) {

        User user;
        try {
            user = userRepository.findById(request.getUserId());
        } catch (SQLException e) {
            onDatabaseError(responseObserver, e);
            return;
        }

        LoginControllerResponse.Builder builder = LoginControllerResponse.newBuilder();
        if (user == null) {
            PasswordHasher.matches(request.getPassword(), DUMMY_PASSWORD_HASH);
        } else if (PasswordHasher.matches(request.getPassword(), user.getPasswordHash())) {
            builder.setSuccess(true)
                    .setToken(jwtProvider.issue(user))
                    .setUserId(user.getUserId())
                    .setName(user.getName())
                    .setRole(user.getRole());
        }

        responseObserver.onNext(builder.build());
        responseObserver.onCompleted();
    }

    @Override
    public void getStudentListController(
            GetStudentListControllerRequest request,
            StreamObserver<GetStudentListControllerResponse> responseObserver) {

        ArrayList<Student> studentList;
        try {
            studentList = studentRepository.findAll();
        } catch (SQLException e) {
            onDatabaseError(responseObserver, e);
            return;
        }

        GetStudentListControllerResponse.Builder builder =
                GetStudentListControllerResponse.newBuilder();

        for (Student student : studentList) {
            StudentInfo info = StudentInfo.newBuilder()
                    .setStudentId(student.getStudentId())
                    .setName(student.getName())
                    .setDepartment(student.getDepartment())
                    .addAllCompletedCourseIds(student.getCompletedCourses())
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

        ArrayList<Course> courseList;
        try {
            courseList = courseRepository.findAll();
        } catch (SQLException e) {
            onDatabaseError(responseObserver, e);
            return;
        }

        GetCourseListControllerResponse.Builder builder =
                GetCourseListControllerResponse.newBuilder();

        for (Course course : courseList) {
            CourseInfo info = CourseInfo.newBuilder()
                    .setCourseId(course.getCourseId())
                    .setCourseName(course.getName())
                    .setInstructorName(course.getInstructorName())
                    .addAllPrerequisiteCourseIds(course.getPrerequisiteCourses())
                    .build();

            builder.addCourses(info);
        }

        GetCourseListControllerResponse response = builder
                .setSuccess(true)
                .build();
        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    @Override
    public void addStudentController(
            AddStudentControllerRequest request,
            StreamObserver<AddStudentControllerResponse> responseObserver) {

        StudentInfo info = request.getStudent();
        Student student = new Student(info.getStudentId(),
                info.getName(),
                info.getDepartment(),
                new ArrayList<String>(info.getCompletedCourseIdsList()));

        boolean success;
        try {
            success = studentRepository.add(student);
        } catch (SQLException e) {
            onDatabaseError(responseObserver, e);
            return;
        }

        AddStudentControllerResponse response = AddStudentControllerResponse.newBuilder()
                .setSuccess(success)
                .build();
        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    @Override
    public void deleteStudentController(
            DeleteStudentControllerRequest request,
            StreamObserver<DeleteStudentControllerResponse> responseObserver) {

        boolean success;
        try {
            success = studentRepository.delete(request.getStudentId());
        } catch (SQLException e) {
            onDatabaseError(responseObserver, e);
            return;
        }

        DeleteStudentControllerResponse response = DeleteStudentControllerResponse.newBuilder()
                .setSuccess(success)
                .build();
        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    @Override
    public void addCourseController(
            AddCourseControllerRequest request,
            StreamObserver<AddCourseControllerResponse> responseObserver) {

        CourseInfo info = request.getCourse();
        Course course = new Course(info.getCourseId(),
                info.getCourseName(),
                info.getInstructorName(),
                new ArrayList<String>(info.getPrerequisiteCourseIdsList()));

        boolean success;
        try {
            success = courseRepository.add(course);
        } catch (SQLException e) {
            onDatabaseError(responseObserver, e);
            return;
        }
        if (success) {
            catalog.invalidate();
        }

        AddCourseControllerResponse response = AddCourseControllerResponse.newBuilder()
                .setSuccess(success)
                .build();
        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    @Override
    public void deleteCourseController(
            DeleteCourseControllerRequest request,
            StreamObserver<DeleteCourseControllerResponse> responseObserver) {

        boolean success;
        try {
            success = courseRepository.delete(request.getCourseId());
        } catch (SQLException e) {
            onDatabaseError(responseObserver, e);
            return;
        }
        if (success) {
            catalog.invalidate();
        }

        DeleteCourseControllerResponse response = DeleteCourseControllerResponse.newBuilder()
                .setSuccess(success)
                .build();
        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    private void onDatabaseError(StreamObserver<?> responseObserver, SQLException e) {
        responseObserver.onError(io.grpc.Status.INTERNAL
                .withDescription("데이터베이스 오류가 발생했습니다.")
                .withCause(e)
                .asRuntimeException());
    }
}
