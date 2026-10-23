package com.serverarchitecture.client;

import com.serverarchitecture.CourseInfo;
import com.serverarchitecture.StudentInfo;
import io.grpc.StatusRuntimeException;

import java.util.List;

public class ProfessorMenu {
    private final ConsoleView view;
    private final AcademicClient academic;

    public ProfessorMenu(ConsoleView view, AcademicClient academic) {
        this.view = view;
        this.academic = academic;
    }

    public void run() {
        while (true) {
            view.printMenu("교수 메뉴",
                    "1. 학생 목록",
                    "2. 과목 목록",
                    "3. 학생별 수강과목",
                    "4. 과목별 수강학생",
                    "5. 학생별 이수과목",
                    "6. 학생 추가",
                    "7. 학생 삭제",
                    "8. 과목 추가",
                    "9. 과목 삭제",
                    "X. 종료");

            String choice = view.readLine("메뉴 선택: ");

            try {
                switch (choice.toUpperCase()) {
                    case "1":
                        view.println("[학생 목록]");
                        view.printStudents(academic.listStudents());
                        break;
                    case "2":
                        view.println("[과목 목록]");
                        view.printCourses(academic.listCourses());
                        break;
                    case "3":
                        coursesByStudent();
                        break;
                    case "4":
                        studentsByCourse();
                        break;
                    case "5":
                        completedCourses();
                        break;
                    case "6":
                        addStudent();
                        break;
                    case "7":
                        deleteStudent();
                        break;
                    case "8":
                        addCourse();
                        break;
                    case "9":
                        deleteCourse();
                        break;
                    case "X":
                        view.println("종료합니다.");
                        return;
                    default:
                        view.printInvalidChoice(choice, 9);
                }
            } catch (StatusRuntimeException e) {
                view.printError(e);
            }
        }
    }

    private void coursesByStudent() {
        view.println("[학생별 수강과목]");
        String studentId = view.readRequired("학번: ");
        if (studentId == null) {
            return;
        }
        view.printCourses(academic.listCoursesByStudent(studentId));
    }

    private void studentsByCourse() {
        view.println("[과목별 수강학생]");
        String courseId = view.readRequired("과목번호: ");
        if (courseId == null) {
            return;
        }
        view.printStudents(academic.listStudentsByCourse(courseId));
    }

    private void completedCourses() {
        view.println("[학생별 이수과목]");
        String studentId = view.readRequired("학번: ");
        if (studentId == null) {
            return;
        }
        view.printCourses(academic.listCompletedCourses(studentId));
    }

    private void addStudent() {
        view.println("[학생 추가]");
        String studentId = view.readRequired("학번: ");
        if (studentId == null) {
            return;
        }
        String name = view.readRequired("이름: ");
        if (name == null) {
            return;
        }
        String department = view.readRequired("학과: ");
        if (department == null) {
            return;
        }
        Integer maxCredits = view.readOptionalNumber("최대 학점 (엔터: 18): ");
        if (maxCredits == null) {
            return;
        }
        List<String> completed = view.readList("이수 과목번호 (쉼표나 공백으로 구분, 없으면 엔터): ");

        StudentInfo student = academic.addStudent(StudentInfo.newBuilder()
                .setStudentId(studentId)
                .setName(name)
                .setDepartment(department)
                .setMaxCredits(maxCredits)
                .addAllCompletedCourseIds(completed)
                .build());
        view.println("학생을 추가했습니다. " + student.getStudentId() + " " + student.getName()
                + " (초기 비밀번호는 학번)");
    }

    private void deleteStudent() {
        view.println("[학생 삭제]");
        String studentId = view.readRequired("학번: ");
        if (studentId == null) {
            return;
        }
        view.println("학생을 삭제했습니다. " + academic.deleteStudent(studentId));
    }

    private void addCourse() {
        view.println("[과목 추가]");
        String courseId = view.readRequired("과목번호: ");
        if (courseId == null) {
            return;
        }
        String courseName = view.readRequired("과목명: ");
        if (courseName == null) {
            return;
        }
        String instructorName = view.readRequired("담당교수: ");
        if (instructorName == null) {
            return;
        }
        Integer credits = view.readOptionalNumber("학점 (엔터: 3): ");
        if (credits == null) {
            return;
        }
        List<String> prerequisites = view.readList("선수 과목번호 (쉼표나 공백으로 구분, 없으면 엔터): ");

        CourseInfo course = academic.addCourse(CourseInfo.newBuilder()
                .setCourseId(courseId)
                .setCourseName(courseName)
                .setInstructorName(instructorName)
                .setCredits(credits)
                .addAllPrerequisiteCourseIds(prerequisites)
                .build());
        view.println("과목을 추가했습니다. " + course.getCourseId() + " " + course.getCourseName());
    }

    private void deleteCourse() {
        view.println("[과목 삭제]");
        String courseId = view.readRequired("과목번호: ");
        if (courseId == null) {
            return;
        }
        view.println("과목을 삭제했습니다. " + academic.deleteCourse(courseId));
    }
}
