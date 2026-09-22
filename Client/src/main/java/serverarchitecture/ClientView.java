package serverarchitecture;

import com.serverarchitecture.CourseInfo;
import com.serverarchitecture.GetCourseListControllerResponse;
import com.serverarchitecture.GetStudentListControllerResponse;
import com.serverarchitecture.StudentInfo;

import java.util.*;

public class ClientView {
    Scanner scanner = new Scanner(System.in);

    public void showView(GetStudentListControllerResponse studentListResponse, GetCourseListControllerResponse courseListResponse) {

        while (true) {
            while (true) {
                System.out.println("""
                        
                        1. 학생 목록 조회
                        2. 과목 목록 조회
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

                switch (selection) {
                    case 1:
                        System.out.println("학생 목록 조회 선택");

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

                    case 0:
                        System.out.println("종료합니다.");
                        return;

                    default:
                        System.out.println("0, 1, 2 중에서 선택해 주세요.");
                }
            }
        }

    }
}
