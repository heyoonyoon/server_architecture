package serverarchitecture;

import com.serverarchitecture.*;
import io.grpc.StatusRuntimeException;

import java.util.*;

public class ClientView {
    Scanner scanner = new Scanner(System.in);

    public void setData(ServerClientServiceGrpc.ServerClientServiceBlockingStub stub) {

        while (true) {
            while (true) {
                System.out.println("""
                        
                        1. 학생 목록 조회
                        2. 과목 목록 조회
                        3. 학생 추가
                        4. 학생 삭제
                        5. 과목 추가
                        6. 과목 삭제
                        0. 종료
                        """);

                System.out.print("메뉴 번호 입력: ");
                System.out.flush();

                if (!scanner.hasNextLine()) {
                    return;
                }

                String input = scanner.nextLine().strip();

                int selection;
                try {
                    selection = Integer.parseInt(input);
                } catch (NumberFormatException e) {
                    System.out.println("숫자로 입력해 주세요.");
                    continue;
                }

                try {
                    switch (selection) {
                        case 1:
                            System.out.println("학생 목록 조회 선택");

                            GetStudentListControllerResponse studentListResponse = stub.getStudentListController(
                                    GetStudentListControllerRequest.newBuilder().build());

                            for (StudentInfo student : studentListResponse.getStudentsList()) {
                                System.out.println("------------------------------");
                                System.out.println("학번: " + student.getStudentId());
                                System.out.println("이름: " + student.getName());
                                System.out.println("학과: " + student.getDepartment());

                                String completedCourses = student.getCompletedCourseIdsList().isEmpty()
                                        ? "없음"
                                        : String.join(", ", student.getCompletedCourseIdsList());

                                System.out.println("이수 과목: " + completedCourses);
                            }

                            break;

                        case 2:
                            System.out.println("과목 목록 조회 선택");

                            GetCourseListControllerResponse courseListResponse = stub.getCourseListController(
                                    GetCourseListControllerRequest.newBuilder().build());

                            for (CourseInfo course : courseListResponse.getCoursesList()) {
                                System.out.println("------------------------------");
                                System.out.println("과목 번호: " + course.getCourseId());
                                System.out.println("과목 이름: " + course.getCourseName());
                                System.out.println("담당 교수: " + course.getInstructorName());

                                String prerequisites = course.getPrerequisiteCourseIdsList().isEmpty()
                                        ? "없음"
                                        : String.join(", ", course.getPrerequisiteCourseIdsList());

                                System.out.println("선수 과목: " + prerequisites);
                            }
                            break;

                        case 3:
                            System.out.println("학생 추가 선택");

                            String studentId = readRequired("학번: ");
                            if (studentId == null) {
                                break;
                            }
                            String name = readRequired("이름: ");
                            if (name == null) {
                                break;
                            }
                            String department = readRequired("학과: ");
                            if (department == null) {
                                break;
                            }
                            List<String> completedCourseIds = readList("이수 과목(공백으로 구분, 없으면 엔터): ");

                            StudentInfo newStudent = StudentInfo.newBuilder()
                                    .setStudentId(studentId)
                                    .setName(name)
                                    .setDepartment(department)
                                    .addAllCompletedCourseIds(completedCourseIds)
                                    .build();

                            AddStudentControllerResponse addStudentResponse = stub.addStudentController(
                                    AddStudentControllerRequest.newBuilder()
                                            .setStudent(newStudent)
                                            .build());

                            System.out.println(addStudentResponse.getSuccess()
                                    ? "학생을 추가했습니다."
                                    : "이미 존재하는 학번입니다.");
                            break;

                        case 4:
                            System.out.println("학생 삭제 선택");

                            String deleteStudentId = readRequired("학번: ");
                            if (deleteStudentId == null) {
                                break;
                            }

                            DeleteStudentControllerResponse deleteStudentResponse = stub.deleteStudentController(
                                    DeleteStudentControllerRequest.newBuilder()
                                            .setStudentId(deleteStudentId)
                                            .build());

                            System.out.println(deleteStudentResponse.getSuccess()
                                    ? "학생을 삭제했습니다."
                                    : "존재하지 않는 학번입니다.");
                            break;

                        case 5:
                            System.out.println("과목 추가 선택");

                            String courseId = readRequired("과목 번호: ");
                            if (courseId == null) {
                                break;
                            }
                            String courseName = readRequired("과목 이름: ");
                            if (courseName == null) {
                                break;
                            }
                            String instructorName = readRequired("담당 교수: ");
                            if (instructorName == null) {
                                break;
                            }
                            List<String> prerequisiteCourseIds = readList("선수 과목(공백으로 구분, 없으면 엔터): ");

                            CourseInfo newCourse = CourseInfo.newBuilder()
                                    .setCourseId(courseId)
                                    .setCourseName(courseName)
                                    .setInstructorName(instructorName)
                                    .addAllPrerequisiteCourseIds(prerequisiteCourseIds)
                                    .build();

                            AddCourseControllerResponse addCourseResponse = stub.addCourseController(
                                    AddCourseControllerRequest.newBuilder()
                                            .setCourse(newCourse)
                                            .build());

                            System.out.println(addCourseResponse.getSuccess()
                                    ? "과목을 추가했습니다."
                                    : "이미 존재하는 과목 번호입니다.");
                            break;

                        case 6:
                            System.out.println("과목 삭제 선택");

                            String deleteCourseId = readRequired("과목 번호: ");
                            if (deleteCourseId == null) {
                                break;
                            }

                            DeleteCourseControllerResponse deleteCourseResponse = stub.deleteCourseController(
                                    DeleteCourseControllerRequest.newBuilder()
                                            .setCourseId(deleteCourseId)
                                            .build());

                            System.out.println(deleteCourseResponse.getSuccess()
                                    ? "과목을 삭제했습니다."
                                    : "존재하지 않는 과목 번호입니다.");
                            break;

                        case 0:
                            System.out.println("종료합니다.");
                            return;

                        default:
                            System.out.println("0~6 중에서 선택해 주세요.");
                    }
                } catch (StatusRuntimeException e) {
                    System.out.println("서버 요청에 실패했습니다: " + e.getStatus().getDescription());
                }
            }
        }

    }

    private String readRequired(String prompt) {
        System.out.print(prompt);
        System.out.flush();

        if (!scanner.hasNextLine()) {
            return null;
        }

        String input = scanner.nextLine().strip();
        if (input.isEmpty()) {
            System.out.println("값을 입력해 주세요.");
            return null;
        }
        return input;
    }

    private List<String> readList(String prompt) {
        System.out.print(prompt);
        System.out.flush();

        if (!scanner.hasNextLine()) {
            return new ArrayList<String>();
        }

        String input = scanner.nextLine().strip();
        if (input.isEmpty()) {
            return new ArrayList<String>();
        }
        return Arrays.asList(input.split("\\s+"));
    }
}
