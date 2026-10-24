package com.serverarchitecture.academic;

import com.serverarchitecture.AcademicServiceGrpc;
import com.serverarchitecture.AccessLogInterceptor;
import com.serverarchitecture.AddCourseRequest;
import com.serverarchitecture.AddStudentRequest;
import com.serverarchitecture.AuthInterceptor;
import com.serverarchitecture.BearerCredentials;
import com.serverarchitecture.Catalog;
import com.serverarchitecture.CourseInfo;
import com.serverarchitecture.CourseList;
import com.serverarchitecture.DataClient;
import com.serverarchitecture.DataCollections;
import com.serverarchitecture.DeleteCourseRequest;
import com.serverarchitecture.DeleteStudentRequest;
import com.serverarchitecture.GetCatalogRequest;
import com.serverarchitecture.GetStudentRequest;
import com.serverarchitecture.JwtProvider;
import com.serverarchitecture.ListCompletedCoursesRequest;
import com.serverarchitecture.ListCoursesByStudentRequest;
import com.serverarchitecture.ListCoursesRequest;
import com.serverarchitecture.ListStudentsByCourseRequest;
import com.serverarchitecture.ListStudentsRequest;
import com.serverarchitecture.OfferingInfo;
import com.serverarchitecture.PasswordHasher;
import com.serverarchitecture.Role;
import com.serverarchitecture.SeedLoader;
import com.serverarchitecture.StudentInfo;
import com.serverarchitecture.User;
import com.serverarchitecture.UserRepository;
import com.serverarchitecture.data.api.Item;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AcademicServiceImplTest {
    private static final String SEMESTER = "2026-2";
    private static final String PRESENTER = "60220435";

    private final JwtProvider jwtProvider = new JwtProvider("academic-service-test-secret-0123456789", Duration.ofHours(1));
    private FakeDataService fake;
    private DataClient data;
    private Server server;
    private ManagedChannel channel;

    @BeforeEach
    void setUp() throws Exception {
        fake = new FakeDataService();
        data = fake.client();
        for (String collection : List.of(DataCollections.USERS, DataCollections.STUDENTS,
                DataCollections.COURSES, DataCollections.OFFERINGS)) {
            SeedLoader.seedIfEmpty(data, collection);
        }
        AcademicServiceImpl service = new AcademicServiceImpl(new StudentRepository(data), new CourseRepository(data),
                new OfferingRepository(data), new EnrollmentRepository(data), new UserRepository(data));
        String name = InProcessServerBuilder.generateName();
        server = InProcessServerBuilder.forName(name).directExecutor()
                .addService(ServerInterceptors.intercept(service,
                        new AuthInterceptor(jwtProvider, AcademicServer.PERMISSIONS),
                        new AccessLogInterceptor(jwtProvider)))
                .build()
                .start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();
    }

    @AfterEach
    void tearDown() {
        channel.shutdownNow();
        server.shutdownNow();
        fake.close();
    }

    @Test
    void listsSeededStudentsAndCourses() {
        assertEquals(18, professor().listStudents(ListStudentsRequest.getDefaultInstance()).getStudentsCount());
        CourseList courses = student(PRESENTER).listCourses(ListCoursesRequest.getDefaultInstance());
        assertEquals(7, courses.getCoursesCount());
        CourseInfo architectures = courses.getCourses(6);
        assertEquals("17655", architectures.getCourseId());
        assertEquals(3, architectures.getCredits());
        assertEquals(List.of("12345", "17651"), architectures.getPrerequisiteCourseIdsList());
    }

    @Test
    void studentCannotListAllStudents() {
        assertStatus(Status.Code.PERMISSION_DENIED,
                () -> student(PRESENTER).listStudents(ListStudentsRequest.getDefaultInstance()));
    }

    @Test
    void getsStudentAndReportsMissingOne() {
        StudentInfo presenter = service().getStudent(GetStudentRequest.newBuilder().setStudentId(PRESENTER).build());
        assertEquals("Heeyoon", presenter.getName());
        assertEquals(18, presenter.getMaxCredits());
        assertEquals(0, presenter.getCompletedCourseIdsCount());

        assertStatus(Status.Code.NOT_FOUND,
                () -> service().getStudent(GetStudentRequest.newBuilder().setStudentId("99999999").build()));
    }

    @Test
    void addsStudentWithLoginAccountAndDefaultCredits() {
        StudentInfo added = professor().addStudent(AddStudentRequest.newBuilder()
                .setStudent(StudentInfo.newBuilder().setStudentId("20260001").setName("New Student")
                        .setDepartment("CS").addCompletedCourseIds("12345"))
                .build());

        assertEquals(18, added.getMaxCredits());
        Item stored = data.read(DataCollections.STUDENTS, "20260001").orElseThrow();
        assertEquals("12345", stored.getFieldsOrThrow("completed_course_ids"));
        assertEquals("18", stored.getFieldsOrThrow("max_credits"));
        User user = new UserRepository(data).findById("20260001").orElseThrow();
        assertEquals(Role.STUDENT, user.getRole());
        assertTrue(PasswordHasher.matches("20260001", user.getPasswordHash()));
    }

    @Test
    void rejectsDuplicateOrInvalidStudents() {
        assertStatus(Status.Code.ALREADY_EXISTS, () -> addStudent(PRESENTER, "Dup"));
        assertStatus(Status.Code.ALREADY_EXISTS, () -> addStudent("P001", "Professor Id"));
        assertStatus(Status.Code.INVALID_ARGUMENT, () -> addStudent("bad,id", "Bad"));
        assertStatus(Status.Code.INVALID_ARGUMENT, () -> addStudent("20260002", " "));
        assertEquals(18, data.count(DataCollections.STUDENTS));
    }

    @Test
    void rejectsStudentWithUnknownCompletedCourse() {
        assertStatus(Status.Code.NOT_FOUND, () -> professor().addStudent(AddStudentRequest.newBuilder()
                .setStudent(StudentInfo.newBuilder().setStudentId("20260003").setName("X").addCompletedCourseIds("00000"))
                .build()));
        assertFalse(data.read(DataCollections.STUDENTS, "20260003").isPresent());
        assertFalse(data.read(DataCollections.USERS, "20260003").isPresent());
    }

    @Test
    void deletesStudentWithoutActiveEnrollment() {
        enroll(PRESENTER, 8, "CANCELLED");

        professor().deleteStudent(DeleteStudentRequest.newBuilder().setStudentId(PRESENTER).build());

        assertFalse(data.read(DataCollections.STUDENTS, PRESENTER).isPresent());
        assertFalse(data.read(DataCollections.USERS, PRESENTER).isPresent());
        assertStatus(Status.Code.NOT_FOUND,
                () -> professor().deleteStudent(DeleteStudentRequest.newBuilder().setStudentId(PRESENTER).build()));
    }

    @Test
    void refusesToDeleteEnrolledStudent() {
        enroll(PRESENTER, 8, "ENROLLED");

        assertStatus(Status.Code.FAILED_PRECONDITION,
                () -> professor().deleteStudent(DeleteStudentRequest.newBuilder().setStudentId(PRESENTER).build()));
        assertTrue(data.read(DataCollections.USERS, PRESENTER).isPresent());
    }

    @Test
    void addsCourseWithDefaultCredits() {
        CourseInfo added = professor().addCourse(AddCourseRequest.newBuilder()
                .setCourse(CourseInfo.newBuilder().setCourseId("30001").setCourseName("Distributed_Systems")
                        .setInstructorName("Lee").addPrerequisiteCourseIds("17655"))
                .build());

        assertEquals(3, added.getCredits());
        assertEquals("17655", data.read(DataCollections.COURSES, "30001").orElseThrow()
                .getFieldsOrThrow("prerequisite_course_ids"));
    }

    @Test
    void rejectsInvalidCourses() {
        assertStatus(Status.Code.ALREADY_EXISTS, () -> addCourse("12345"));
        assertStatus(Status.Code.NOT_FOUND, () -> addCourse("30002", "00000"));
        assertStatus(Status.Code.INVALID_ARGUMENT, () -> addCourse("30003", "30003"));
        assertEquals(7, data.count(DataCollections.COURSES));
    }

    @Test
    void deleteCourseFollowsReferenceRules() {
        assertStatus(Status.Code.FAILED_PRECONDITION, () -> deleteCourse("17653"));
        addCourse("30001");
        addCourse("30002", "30001");
        assertStatus(Status.Code.FAILED_PRECONDITION, () -> deleteCourse("30001"));

        deleteCourse("30002");
        deleteCourse("30001");

        assertEquals(7, data.count(DataCollections.COURSES));
        assertStatus(Status.Code.NOT_FOUND, () -> deleteCourse("30001"));
    }

    @Test
    void refusesToDeleteCompletedCourse() {
        addCourse("30001");
        data.update(DataCollections.STUDENTS, DataClient.item(PRESENTER, Map.of("name", "Heeyoon",
                "department", "CS", "max_credits", "18", "completed_course_ids", "30001")));

        assertStatus(Status.Code.FAILED_PRECONDITION, () -> deleteCourse("30001"));
    }

    @Test
    void listsEnrolledCoursesForSelfAndProfessor() {
        enroll(PRESENTER, 8, "ENROLLED");
        enroll(PRESENTER, 11, "ENROLLED");
        enroll(PRESENTER, 9, "CANCELLED");
        ListCoursesByStudentRequest request = ListCoursesByStudentRequest.newBuilder().setStudentId(PRESENTER).build();

        assertEquals(List.of("12345", "17653"), courseIds(student(PRESENTER).listCoursesByStudent(request)));
        assertEquals(List.of("12345", "17653"), courseIds(professor().listCoursesByStudent(request)));
        assertStatus(Status.Code.PERMISSION_DENIED, () -> student("20100123").listCoursesByStudent(request));
        assertStatus(Status.Code.NOT_FOUND, () -> professor().listCoursesByStudent(
                ListCoursesByStudentRequest.newBuilder().setStudentId("99999999").build()));
    }

    @Test
    void listsStudentsAcrossSectionsOfCourse() {
        enroll(PRESENTER, 11, "ENROLLED");
        enroll("20100123", 12, "ENROLLED");
        enroll("20090421", 12, "CANCELLED");

        List<StudentInfo> students = professor().listStudentsByCourse(
                ListStudentsByCourseRequest.newBuilder().setCourseId("17653").build()).getStudentsList();

        assertEquals(List.of(PRESENTER, "20100123"), students.stream().map(StudentInfo::getStudentId).toList());
        assertStatus(Status.Code.NOT_FOUND, () -> professor().listStudentsByCourse(
                ListStudentsByCourseRequest.newBuilder().setCourseId("00000").build()));
    }

    @Test
    void listsCompletedCoursesWithSelfCheck() {
        ListCompletedCoursesRequest request = ListCompletedCoursesRequest.newBuilder().setStudentId("20100123").build();

        assertEquals(List.of("12345", "23456", "17651", "17652"), courseIds(student("20100123").listCompletedCourses(request)));
        assertEquals(4, service().listCompletedCourses(request).getCoursesCount());
        assertStatus(Status.Code.PERMISSION_DENIED, () -> student(PRESENTER).listCompletedCourses(request));
        assertEquals(0, student(PRESENTER).listCompletedCourses(
                ListCompletedCoursesRequest.newBuilder().setStudentId(PRESENTER).build()).getCoursesCount());
    }

    @Test
    void buildsCatalogWithRemainingSeats() {
        enroll(PRESENTER, 8, "ENROLLED");
        enroll("20100123", 8, "ENROLLED");
        enroll("20090421", 8, "CANCELLED");

        Catalog catalog = service().getCatalog(GetCatalogRequest.newBuilder().setSemester(SEMESTER).build());

        assertEquals(8, catalog.getOfferingsCount());
        OfferingInfo java = catalog.getOfferings(0);
        assertEquals(8, java.getOfferingId());
        assertEquals("Java_Programming", java.getCourseName());
        assertEquals("Park", java.getProfessorName());
        assertEquals(30, java.getCapacity());
        assertEquals(28, java.getRemaining());
        assertEquals(2, java.getTimesCount());
        assertEquals("WED", java.getTimes(1).getDayOfWeek());
        assertEquals("10:30", java.getTimes(1).getEndTime());
        OfferingInfo architectures = catalog.getOfferingsList().stream()
                .filter(offering -> offering.getOfferingId() == 14).findFirst().orElseThrow();
        assertEquals(List.of("12345", "17651"), architectures.getPrerequisiteCourseIdsList());
    }

    @Test
    void loadTestSeedAddsStudentsAndOfferingsToCatalog() throws Exception {
        SeedLoader.seedLoadTestWithRetry(data, DataCollections.STUDENTS, DataCollections.COURSES,
                DataCollections.OFFERINGS);

        StudentInfo load = service().getStudent(GetStudentRequest.newBuilder().setStudentId("30003660").build());
        assertEquals(18, load.getMaxCredits());
        assertEquals(0, load.getCompletedCourseIdsCount());
        Catalog catalog = service().getCatalog(GetCatalogRequest.newBuilder().setSemester(SEMESTER).build());
        assertEquals(18, catalog.getOfferingsCount());
        OfferingInfo writing = catalog.getOfferingsList().stream()
                .filter(offering -> offering.getOfferingId() == 101).findFirst().orElseThrow();
        assertEquals("Writing_and_Communication", writing.getCourseName());
        assertEquals(300, writing.getCapacity());
        assertEquals(300, writing.getRemaining());
        assertEquals(0, writing.getPrerequisiteCourseIdsCount());
    }

    @Test
    void catalogRequiresOfferings() {
        assertStatus(Status.Code.NOT_FOUND,
                () -> service().getCatalog(GetCatalogRequest.newBuilder().setSemester("2030-1").build()));
        assertStatus(Status.Code.INVALID_ARGUMENT,
                () -> service().getCatalog(GetCatalogRequest.getDefaultInstance()));
    }

    @Test
    void dataOutageBecomesUnavailable() {
        fake.stop();

        assertStatus(Status.Code.UNAVAILABLE, () -> professor().listCourses(ListCoursesRequest.getDefaultInstance()));
    }

    private void addStudent(String id, String name) {
        professor().addStudent(AddStudentRequest.newBuilder()
                .setStudent(StudentInfo.newBuilder().setStudentId(id).setName(name))
                .build());
    }

    private void addCourse(String id, String... prerequisites) {
        professor().addCourse(AddCourseRequest.newBuilder()
                .setCourse(CourseInfo.newBuilder().setCourseId(id).setCourseName("Course_" + id).setInstructorName("Kim")
                        .addAllPrerequisiteCourseIds(List.of(prerequisites)))
                .build());
    }

    private void deleteCourse(String id) {
        professor().deleteCourse(DeleteCourseRequest.newBuilder().setCourseId(id).build());
    }

    private void enroll(String studentId, int offeringId, String status) {
        data.create(DataCollections.ENROLLMENTS, DataClient.item(studentId + ":" + offeringId, Map.of(
                "student_id", studentId,
                "offering_id", String.valueOf(offeringId),
                "semester", SEMESTER,
                "status", status,
                "applied_at_ms", "0")));
    }

    private static List<String> courseIds(CourseList courses) {
        return courses.getCoursesList().stream().map(CourseInfo::getCourseId).toList();
    }

    private AcademicServiceGrpc.AcademicServiceBlockingStub professor() {
        return stub(jwtProvider.issue(new User("P001", "Park", Role.PROFESSOR, "")));
    }

    private AcademicServiceGrpc.AcademicServiceBlockingStub student(String studentId) {
        return stub(jwtProvider.issue(new User(studentId, studentId, Role.STUDENT, "")));
    }

    private AcademicServiceGrpc.AcademicServiceBlockingStub service() {
        return stub(jwtProvider.issueService("enrollment-service"));
    }

    private AcademicServiceGrpc.AcademicServiceBlockingStub stub(String token) {
        return AcademicServiceGrpc.newBlockingStub(channel).withCallCredentials(new BearerCredentials(() -> token));
    }

    private static void assertStatus(Status.Code expected, Executable call) {
        StatusRuntimeException e = assertThrows(StatusRuntimeException.class, call);
        assertEquals(expected, e.getStatus().getCode(), e.getStatus().getDescription());
        assertFalse(e.getStatus().getDescription() == null || e.getStatus().getDescription().isBlank());
    }
}
