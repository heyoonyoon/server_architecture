package com.serverarchitecture.client;

import com.serverarchitecture.CourseInfo;
import com.serverarchitecture.OfferingInfo;
import com.serverarchitecture.OfferingTime;
import com.serverarchitecture.StudentInfo;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class ConsoleView {
    private final BufferedReader reader;
    private final PrintStream out;

    public ConsoleView(InputStream in, PrintStream out) {
        this.reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        this.out = out;
    }

    public void println(String text) {
        out.println(text);
        out.flush();
    }

    public String readLine(String prompt) {
        out.print(prompt);
        out.flush();

        String line;
        try {
            line = reader.readLine();
        } catch (IOException e) {
            throw new InputClosedException();
        }
        if (line == null) {
            throw new InputClosedException();
        }
        return line.strip();
    }

    public String readRequired(String prompt) {
        String input = readLine(prompt);
        if (input.isEmpty()) {
            println("값을 입력해 주세요.");
            return null;
        }
        return input;
    }

    public Integer readNumber(String prompt) {
        String input = readRequired(prompt);
        if (input == null) {
            return null;
        }
        Integer number = parseNumber(input);
        if (number == null) {
            println("0 이상의 숫자로 입력해 주세요.");
        }
        return number;
    }

    public Integer readOptionalNumber(String prompt) {
        String input = readLine(prompt);
        if (input.isEmpty()) {
            return 0;
        }
        Integer number = parseNumber(input);
        if (number == null) {
            println("0 이상의 숫자로 입력해 주세요.");
        }
        return number;
    }

    public List<String> readList(String prompt) {
        return parseList(readLine(prompt));
    }

    public void printMenu(String title, String... items) {
        println("");
        println("===== " + title + " =====");
        for (String item : items) {
            println(item);
        }
    }

    public void printInvalidChoice(String input, int lastNumber) {
        println(invalidChoiceMessage(input, lastNumber));
    }

    public void printError(StatusRuntimeException e) {
        println("[오류] " + describe(e.getStatus()));
    }

    public void printStudents(List<StudentInfo> students) {
        if (students.isEmpty()) {
            println("학생이 없습니다.");
            return;
        }
        List<String[]> rows = new ArrayList<String[]>();
        for (StudentInfo student : students) {
            rows.add(new String[]{
                    student.getStudentId(),
                    student.getName(),
                    student.getDepartment(),
                    String.valueOf(student.getMaxCredits()),
                    joinOrNone(student.getCompletedCourseIdsList())});
        }
        printTable(new String[]{"학번", "이름", "학과", "최대학점", "이수 과목"}, rows);
        println("총 " + students.size() + "명");
    }

    public void printCourses(List<CourseInfo> courses) {
        if (courses.isEmpty()) {
            println("과목이 없습니다.");
            return;
        }
        List<String[]> rows = new ArrayList<String[]>();
        for (CourseInfo course : courses) {
            rows.add(new String[]{
                    course.getCourseId(),
                    course.getCourseName(),
                    course.getInstructorName(),
                    String.valueOf(course.getCredits()),
                    joinOrNone(course.getPrerequisiteCourseIdsList())});
        }
        printTable(new String[]{"과목번호", "과목명", "담당교수", "학점", "선수 과목"}, rows);
        println("총 " + courses.size() + "과목");
    }

    public void printOfferings(List<OfferingInfo> offerings) {
        if (offerings.isEmpty()) {
            println("개설 강의가 없습니다.");
            return;
        }
        List<String[]> rows = new ArrayList<String[]>();
        for (OfferingInfo offering : offerings) {
            rows.add(new String[]{
                    String.valueOf(offering.getOfferingId()),
                    offering.getCourseId(),
                    offering.getCourseName(),
                    offering.getSection(),
                    offering.getProfessorName(),
                    String.valueOf(offering.getCredits()),
                    formatTimes(offering.getTimesList()),
                    offering.getRoom(),
                    offering.getRemaining() + "/" + offering.getCapacity()});
        }
        printTable(new String[]{"강의번호", "과목번호", "과목명", "분반", "교수", "학점", "시간", "강의실", "잔여/정원"}, rows);
    }

    public void printTable(String[] headers, List<String[]> rows) {
        for (String line : formatTable(headers, rows)) {
            println(line);
        }
    }

    static List<String> formatTable(String[] headers, List<String[]> rows) {
        int[] widths = new int[headers.length];
        for (int i = 0; i < headers.length; i++) {
            widths[i] = displayWidth(headers[i]);
        }
        for (String[] row : rows) {
            for (int i = 0; i < headers.length; i++) {
                widths[i] = Math.max(widths[i], displayWidth(cell(row, i)));
            }
        }

        List<String> lines = new ArrayList<String>();
        lines.add(formatRow(headers, widths));
        StringBuilder rule = new StringBuilder();
        for (int i = 0; i < widths.length; i++) {
            if (i > 0) {
                rule.append("-+-");
            }
            rule.append("-".repeat(widths[i]));
        }
        lines.add(rule.toString());
        for (String[] row : rows) {
            lines.add(formatRow(row, widths));
        }
        return lines;
    }

    private static String formatRow(String[] row, int[] widths) {
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < widths.length; i++) {
            if (i > 0) {
                line.append(" | ");
            }
            line.append(pad(cell(row, i), widths[i]));
        }
        return line.toString().stripTrailing();
    }

    private static String cell(String[] row, int index) {
        return index < row.length && row[index] != null ? row[index] : "";
    }

    static String pad(String text, int width) {
        return text + " ".repeat(Math.max(width - displayWidth(text), 0));
    }

    static int displayWidth(String text) {
        int width = 0;
        for (int i = 0; i < text.length(); i++) {
            width += isWide(text.charAt(i)) ? 2 : 1;
        }
        return width;
    }

    private static boolean isWide(char c) {
        return (c >= 0x1100 && c <= 0x115F)
                || (c >= 0x2E80 && c <= 0xA4CF)
                || (c >= 0xAC00 && c <= 0xD7A3)
                || (c >= 0xF900 && c <= 0xFAFF)
                || (c >= 0xFE30 && c <= 0xFE4F)
                || (c >= 0xFF00 && c <= 0xFF60)
                || (c >= 0xFFE0 && c <= 0xFFE6);
    }

    static String formatTimes(List<OfferingTime> times) {
        if (times.isEmpty()) {
            return "-";
        }
        List<String> parts = new ArrayList<String>();
        for (OfferingTime time : times) {
            parts.add(dayName(time.getDayOfWeek()) + " " + time.getStartTime() + "-" + time.getEndTime());
        }
        return String.join(", ", parts);
    }

    private static String dayName(String day) {
        switch (day) {
            case "MON":
                return "월";
            case "TUE":
                return "화";
            case "WED":
                return "수";
            case "THU":
                return "목";
            case "FRI":
                return "금";
            case "SAT":
                return "토";
            case "SUN":
                return "일";
            default:
                return day;
        }
    }

    static String joinOrNone(List<String> values) {
        return values.isEmpty() ? "없음" : String.join(", ", values);
    }

    static Integer parseNumber(String input) {
        if (input == null || input.isEmpty() || input.length() > 9) {
            return null;
        }
        for (int i = 0; i < input.length(); i++) {
            if (input.charAt(i) < '0' || input.charAt(i) > '9') {
                return null;
            }
        }
        return Integer.parseInt(input);
    }

    static List<String> parseList(String input) {
        String trimmed = input == null ? "" : input.strip();
        if (trimmed.isEmpty()) {
            return new ArrayList<String>();
        }
        List<String> values = new ArrayList<String>();
        for (String value : Arrays.asList(trimmed.split("[\\s,]+"))) {
            if (!value.isEmpty()) {
                values.add(value);
            }
        }
        return values;
    }

    static String invalidChoiceMessage(String input, int lastNumber) {
        if (input.isEmpty()) {
            return "메뉴 번호를 입력해 주세요.";
        }
        if (parseNumber(input) == null) {
            return "숫자(1~" + lastNumber + ") 또는 X를 입력해 주세요.";
        }
        return "없는 메뉴 번호입니다. 1~" + lastNumber + " 또는 X를 입력해 주세요.";
    }

    static String describe(Status status) {
        String description = status.getDescription();
        if (description != null && containsHangul(description)) {
            return description;
        }
        String message = defaultMessage(status.getCode());
        if (description == null || description.isBlank()) {
            return message;
        }
        return message + " (" + description + ")";
    }

    private static String defaultMessage(Status.Code code) {
        switch (code) {
            case UNAVAILABLE:
                return "서버에 연결할 수 없습니다.";
            case DEADLINE_EXCEEDED:
                return "서버 응답 시간이 초과되었습니다.";
            case UNAUTHENTICATED:
                return "로그인이 필요합니다.";
            case PERMISSION_DENIED:
                return "권한이 없습니다.";
            case NOT_FOUND:
                return "찾을 수 없습니다.";
            case ALREADY_EXISTS:
                return "이미 존재합니다.";
            case INVALID_ARGUMENT:
                return "입력값이 올바르지 않습니다.";
            case UNIMPLEMENTED:
                return "서버에서 아직 지원하지 않는 기능입니다.";
            case CANCELLED:
                return "요청이 취소되었습니다.";
            default:
                return "요청을 처리하지 못했습니다. " + code;
        }
    }

    private static boolean containsHangul(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 0xAC00 && c <= 0xD7A3) {
                return true;
            }
        }
        return false;
    }
}
