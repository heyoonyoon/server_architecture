package com.serverarchitecture.academic;

import com.serverarchitecture.AcademicServiceGrpc;
import com.serverarchitecture.AddCourseRequest;
import com.serverarchitecture.AddStudentRequest;
import com.serverarchitecture.AuthInterceptor;
import com.serverarchitecture.AuthenticatedUser;
import com.serverarchitecture.Catalog;
import com.serverarchitecture.CourseInfo;
import com.serverarchitecture.CourseList;
import com.serverarchitecture.CustomException;
import com.serverarchitecture.DeleteCourseRequest;
import com.serverarchitecture.DeleteCourseResponse;
import com.serverarchitecture.DeleteStudentRequest;
import com.serverarchitecture.DeleteStudentResponse;
import com.serverarchitecture.GetCatalogRequest;
import com.serverarchitecture.GetStudentRequest;
import com.serverarchitecture.ListCompletedCoursesRequest;
import com.serverarchitecture.ListCoursesByStudentRequest;
import com.serverarchitecture.ListCoursesRequest;
import com.serverarchitecture.ListStudentsByCourseRequest;
import com.serverarchitecture.ListStudentsRequest;
import com.serverarchitecture.NullDataException;
import com.serverarchitecture.OfferingInfo;
import com.serverarchitecture.OfferingTime;
import com.serverarchitecture.PasswordHasher;
import com.serverarchitecture.Role;
import com.serverarchitecture.StudentInfo;
import com.serverarchitecture.StudentList;
import com.serverarchitecture.User;
import com.serverarchitecture.UserRepository;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.serverarchitecture.GrpcResponder.respond;

public class AcademicServiceImpl extends AcademicServiceGrpc.AcademicServiceImplBase {
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{1,20}");
    private static final Pattern TIME = Pattern.compile("([A-Z]{3}) (\\d{2}:\\d{2})-(\\d{2}:\\d{2})");
    private static final int DEFAULT_MAX_CREDITS = 18;
    private static final int DEFAULT_CREDITS = 3;

    private final StudentRepository studentRepository;
    private final CourseRepository courseRepository;
    private final OfferingRepository offeringRepository;
    private final EnrollmentRepository enrollmentRepository;
    private final UserRepository userRepository;

    public AcademicServiceImpl(StudentRepository studentRepository, CourseRepository courseRepository,
                               OfferingRepository offeringRepository, EnrollmentRepository enrollmentRepository,
                               UserRepository userRepository) {
        this.studentRepository = studentRepository;
        this.courseRepository = courseRepository;
        this.offeringRepository = offeringRepository;
        this.enrollmentRepository = enrollmentRepository;
        this.userRepository = userRepository;
    }

    @Override
    public void listStudents(ListStudentsRequest request, StreamObserver<StudentList> responseObserver) {
        respond(responseObserver, () -> toStudentList(studentRepository.findAll()));
    }

    @Override
    public void listCourses(ListCoursesRequest request, StreamObserver<CourseList> responseObserver) {
        respond(responseObserver, () -> toCourseList(courseRepository.findAll()));
    }

    @Override
    public void getStudent(GetStudentRequest request, StreamObserver<StudentInfo> responseObserver) {
        respond(responseObserver, () -> toInfo(requireStudent(request.getStudentId())));
    }

    @Override
    public void addStudent(AddStudentRequest request, StreamObserver<StudentInfo> responseObserver) {
        respond(responseObserver, () -> toInfo(addStudent(request.getStudent())));
    }

    @Override
    public void deleteStudent(DeleteStudentRequest request, StreamObserver<DeleteStudentResponse> responseObserver) {
        respond(responseObserver, () -> {
            deleteStudent(request.getStudentId());
            return DeleteStudentResponse.newBuilder().setStudentId(request.getStudentId()).build();
        });
    }

    @Override
    public void addCourse(AddCourseRequest request, StreamObserver<CourseInfo> responseObserver) {
        respond(responseObserver, () -> toInfo(addCourse(request.getCourse())));
    }

    @Override
    public void deleteCourse(DeleteCourseRequest request, StreamObserver<DeleteCourseResponse> responseObserver) {
        respond(responseObserver, () -> {
            deleteCourse(request.getCourseId());
            return DeleteCourseResponse.newBuilder().setCourseId(request.getCourseId()).build();
        });
    }

    @Override
    public void listCoursesByStudent(ListCoursesByStudentRequest request, StreamObserver<CourseList> responseObserver) {
        respond(responseObserver, () -> {
            requireSelfOrStaff(request.getStudentId());
            requireStudent(request.getStudentId());
            Set<String> courseIds = new LinkedHashSet<String>();
            for (int offeringId : enrollmentRepository.enrolledOfferingIds(request.getStudentId())) {
                courseIds.add(offeringRepository.findById(offeringId)
                        .orElseThrow(() -> new NullDataException("개설 강의를 찾을 수 없습니다: " + offeringId))
                        .courseId());
            }
            return toCourseList(courseIds.stream().map(this::requireCourse).toList());
        });
    }

    @Override
    public void listStudentsByCourse(ListStudentsByCourseRequest request, StreamObserver<StudentList> responseObserver) {
        respond(responseObserver, () -> {
            requireCourse(request.getCourseId());
            Set<String> studentIds = new LinkedHashSet<String>();
            for (Offering offering : offeringRepository.findByCourse(request.getCourseId())) {
                studentIds.addAll(enrollmentRepository.enrolledStudentIds(offering.offeringId()));
            }
            return toStudentList(studentIds.stream().map(this::requireStudent).toList());
        });
    }

    @Override
    public void listCompletedCourses(ListCompletedCoursesRequest request, StreamObserver<CourseList> responseObserver) {
        respond(responseObserver, () -> {
            requireSelfOrStaff(request.getStudentId());
            Student student = requireStudent(request.getStudentId());
            return toCourseList(student.getCompletedCourses().stream().map(this::requireCourse).toList());
        });
    }

    @Override
    public void getCatalog(GetCatalogRequest request, StreamObserver<Catalog> responseObserver) {
        respond(responseObserver, () -> catalog(request.getSemester()));
    }

    private Student addStudent(StudentInfo info) {
        String studentId = info.getStudentId().trim();
        requireId(studentId, "학번");
        requireText(info.getName(), "이름");
        if (info.getMaxCredits() < 0) {
            throw invalid("최대 학점은 0 이상이어야 합니다.");
        }
        List<String> completed = info.getCompletedCourseIdsList().stream().map(String::trim).distinct().toList();
        completed.forEach(this::requireCourse);
        if (userRepository.findById(studentId).isPresent()) {
            throw alreadyExists("이미 사용 중인 아이디입니다: " + studentId);
        }

        Student student = new Student(studentId, info.getName().trim(), info.getDepartment().trim(),
                info.getMaxCredits() == 0 ? DEFAULT_MAX_CREDITS : info.getMaxCredits(), completed);
        createOrConflict(() -> studentRepository.create(student), "이미 있는 학번입니다: " + studentId);
        try {
            createOrConflict(() -> userRepository.create(new User(studentId, student.getName(), Role.STUDENT,
                    PasswordHasher.hash(studentId))), "이미 사용 중인 아이디입니다: " + studentId);
        } catch (RuntimeException e) {
            studentRepository.delete(studentId);
            throw e;
        }
        return student;
    }

    private void deleteStudent(String studentId) {
        requireStudent(studentId);
        if (!enrollmentRepository.enrolledOfferingIds(studentId).isEmpty()) {
            throw new CustomException(Status.Code.FAILED_PRECONDITION,
                    "수강 신청 내역이 있는 학생은 삭제할 수 없습니다: " + studentId);
        }
        studentRepository.delete(studentId);
        userRepository.findById(studentId)
                .filter(user -> user.getRole() == Role.STUDENT)
                .ifPresent(user -> userRepository.delete(studentId));
    }

    private Course addCourse(CourseInfo info) {
        String courseId = info.getCourseId().trim();
        requireId(courseId, "과목 번호");
        requireText(info.getCourseName(), "과목 이름");
        if (info.getCredits() < 0) {
            throw invalid("학점은 0 이상이어야 합니다.");
        }
        List<String> prerequisites = info.getPrerequisiteCourseIdsList().stream().map(String::trim).distinct().toList();
        if (prerequisites.contains(courseId)) {
            throw invalid("자기 자신을 선수과목으로 지정할 수 없습니다: " + courseId);
        }
        prerequisites.forEach(this::requireCourse);

        Course course = new Course(courseId, info.getCourseName().trim(), info.getInstructorName().trim(),
                info.getCredits() == 0 ? DEFAULT_CREDITS : info.getCredits(), prerequisites);
        createOrConflict(() -> courseRepository.create(course), "이미 있는 과목 번호입니다: " + courseId);
        return course;
    }

    private void deleteCourse(String courseId) {
        requireCourse(courseId);
        if (!offeringRepository.findByCourse(courseId).isEmpty()) {
            throw new CustomException(Status.Code.FAILED_PRECONDITION,
                    "개설 강의가 있는 과목은 삭제할 수 없습니다: " + courseId);
        }
        for (Course course : courseRepository.findAll()) {
            if (course.getPrerequisiteCourses().contains(courseId)) {
                throw new CustomException(Status.Code.FAILED_PRECONDITION,
                        "다른 과목(" + course.getCourseId() + ")의 선수과목이라 삭제할 수 없습니다: " + courseId);
            }
        }
        for (Student student : studentRepository.findAll()) {
            if (student.getCompletedCourses().contains(courseId)) {
                throw new CustomException(Status.Code.FAILED_PRECONDITION,
                        "이수 기록이 있는 과목은 삭제할 수 없습니다: " + courseId);
            }
        }
        courseRepository.delete(courseId);
    }

    private Catalog catalog(String semester) {
        requireText(semester, "학기");
        List<Offering> offerings = offeringRepository.findBySemester(semester.trim());
        if (offerings.isEmpty()) {
            throw new NullDataException("개설 강의가 없습니다: " + semester);
        }
        Map<String, Course> courses = courseRepository.findAll().stream()
                .collect(Collectors.toMap(Course::getCourseId, Function.identity()));
        Map<String, String> professorNames = new HashMap<String, String>();
        for (User professor : userRepository.findByRole(Role.PROFESSOR)) {
            professorNames.put(professor.getUserId(), professor.getName());
        }
        Map<Integer, Integer> enrolled = enrollmentRepository.enrolledCounts(semester.trim());

        Catalog.Builder builder = Catalog.newBuilder();
        for (Offering offering : offerings) {
            Course course = courses.get(offering.courseId());
            if (course == null) {
                throw new NullDataException("과목을 찾을 수 없습니다: " + offering.courseId());
            }
            String professorName = professorNames.get(offering.professorId());
            if (professorName == null) {
                throw new NullDataException("교수를 찾을 수 없습니다: " + offering.professorId());
            }
            int remaining = offering.capacity() - enrolled.getOrDefault(offering.offeringId(), 0);
            builder.addOfferings(OfferingInfo.newBuilder()
                    .setOfferingId(offering.offeringId())
                    .setCourseId(course.getCourseId())
                    .setCourseName(course.getName())
                    .setCredits(course.getCredits())
                    .setSemester(offering.semester())
                    .setSection(offering.section())
                    .setProfessorId(offering.professorId())
                    .setProfessorName(professorName)
                    .setRoom(offering.room())
                    .setCapacity(offering.capacity())
                    .setRemaining(Math.max(0, remaining))
                    .addAllTimes(parseTimes(offering))
                    .addAllPrerequisiteCourseIds(course.getPrerequisiteCourses())
                    .build());
        }
        return builder.build();
    }

    private static List<OfferingTime> parseTimes(Offering offering) {
        return Lists.split(offering.times()).stream().map(slot -> {
            Matcher matcher = TIME.matcher(slot);
            if (!matcher.matches()) {
                throw new IllegalStateException("잘못된 강의 시간 offering=" + offering.offeringId() + ": " + slot);
            }
            return OfferingTime.newBuilder()
                    .setDayOfWeek(matcher.group(1))
                    .setStartTime(matcher.group(2))
                    .setEndTime(matcher.group(3))
                    .build();
        }).toList();
    }

    private void requireSelfOrStaff(String studentId) {
        AuthenticatedUser user = AuthInterceptor.USER.get();
        if (user != null && user.role() == Role.STUDENT && !user.userId().equals(studentId)) {
            throw new CustomException(Status.Code.PERMISSION_DENIED, "본인 정보만 조회할 수 있습니다.");
        }
    }

    private Student requireStudent(String studentId) {
        return studentRepository.findById(studentId)
                .orElseThrow(() -> new NullDataException("학생을 찾을 수 없습니다: " + studentId));
    }

    private Course requireCourse(String courseId) {
        return courseRepository.findById(courseId)
                .orElseThrow(() -> new NullDataException("과목을 찾을 수 없습니다: " + courseId));
    }

    private static void createOrConflict(Runnable create, String message) {
        try {
            create.run();
        } catch (StatusRuntimeException e) {
            if (e.getStatus().getCode() == Status.Code.ALREADY_EXISTS) {
                throw alreadyExists(message);
            }
            throw e;
        }
    }

    private static void requireId(String id, String label) {
        if (!ID.matcher(id).matches()) {
            throw invalid(label + "는 영문, 숫자, '_', '-'로 된 1~20자여야 합니다.");
        }
    }

    private static void requireText(String value, String label) {
        if (value.isBlank()) {
            throw invalid(label + "을(를) 입력하세요.");
        }
    }

    private static CustomException invalid(String message) {
        return new CustomException(Status.Code.INVALID_ARGUMENT, message);
    }

    private static CustomException alreadyExists(String message) {
        return new CustomException(Status.Code.ALREADY_EXISTS, message);
    }

    private static StudentList toStudentList(List<Student> students) {
        return StudentList.newBuilder()
                .addAllStudents(students.stream().map(AcademicServiceImpl::toInfo).toList())
                .build();
    }

    private static CourseList toCourseList(List<Course> courses) {
        return CourseList.newBuilder()
                .addAllCourses(courses.stream().map(AcademicServiceImpl::toInfo).toList())
                .build();
    }

    private static StudentInfo toInfo(Student student) {
        return StudentInfo.newBuilder()
                .setStudentId(student.getStudentId())
                .setName(student.getName())
                .setDepartment(student.getDepartment())
                .setMaxCredits(student.getMaxCredits())
                .addAllCompletedCourseIds(student.getCompletedCourses())
                .build();
    }

    private static CourseInfo toInfo(Course course) {
        return CourseInfo.newBuilder()
                .setCourseId(course.getCourseId())
                .setCourseName(course.getName())
                .setInstructorName(course.getInstructorName())
                .setCredits(course.getCredits())
                .addAllPrerequisiteCourseIds(course.getPrerequisiteCourses())
                .build();
    }
}
