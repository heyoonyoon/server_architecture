package com.serverarchitecture.client;

import com.serverarchitecture.CancelEnrollmentResponse;
import com.serverarchitecture.EnrollResponse;
import com.serverarchitecture.ListMyEnrollmentsResponse;
import com.serverarchitecture.QueueState;
import com.serverarchitecture.QueueStatusResponse;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Iterator;

public class StudentMenu {
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final ConsoleView view;
    private final AcademicClient academic;
    private final EnrollmentClient enrollment;
    private final String studentId;

    public StudentMenu(ConsoleView view, AcademicClient academic, EnrollmentClient enrollment, String studentId) {
        this.view = view;
        this.academic = academic;
        this.enrollment = enrollment;
        this.studentId = studentId;
    }

    public void run() {
        while (true) {
            view.printMenu("학생 메뉴",
                    "1. 과목 목록",
                    "2. 내 수강과목",
                    "3. 내 이수과목",
                    "4. 수강신청",
                    "5. 수강취소",
                    "6. 내 신청 목록",
                    "X. 종료");

            String choice = view.readLine("메뉴 선택: ");

            try {
                switch (choice.toUpperCase()) {
                    case "1":
                        view.println("[과목 목록]");
                        view.printCourses(academic.listCourses());
                        break;
                    case "2":
                        view.println("[내 수강과목]");
                        view.printCourses(academic.listCoursesByStudent(studentId));
                        break;
                    case "3":
                        view.println("[내 이수과목]");
                        view.printCourses(academic.listCompletedCourses(studentId));
                        break;
                    case "4":
                        enroll();
                        break;
                    case "5":
                        cancel();
                        break;
                    case "6":
                        showMyEnrollments();
                        break;
                    case "X":
                        view.println("종료합니다.");
                        return;
                    default:
                        view.printInvalidChoice(choice, 6);
                }
            } catch (StatusRuntimeException e) {
                view.printError(e);
            }
        }
    }

    private void enroll() {
        view.println("[수강신청]");
        if (!waitForAdmission()) {
            return;
        }

        while (true) {
            try {
                view.printOfferings(enrollment.listOfferings());
            } catch (StatusRuntimeException e) {
                view.printError(e);
                checkPassExpired(e);
                return;
            }

            String input = view.readLine("신청할 강의번호 (돌아가기: 엔터 또는 B): ");
            if (input.isEmpty() || input.equalsIgnoreCase("B")) {
                return;
            }
            Integer offeringId = ConsoleView.parseNumber(input);
            if (offeringId == null) {
                view.println("강의번호는 숫자로 입력해 주세요.");
                continue;
            }

            try {
                EnrollResponse response = enrollment.enroll(offeringId);
                view.println("[신청 완료] " + response.getMessage() + " "
                        + response.getOffering().getCourseName() + " (강의번호 " + offeringId + ")");
            } catch (StatusRuntimeException e) {
                view.printError(e);
                if (checkPassExpired(e)) {
                    return;
                }
            }
        }
    }

    private void cancel() {
        view.println("[수강취소]");
        if (!waitForAdmission()) {
            return;
        }

        try {
            ListMyEnrollmentsResponse mine = enrollment.listMyEnrollments();
            if (mine.getOfferingsCount() == 0) {
                view.println("신청한 강의가 없습니다.");
                return;
            }
            view.printOfferings(mine.getOfferingsList());

            Integer offeringId = view.readNumber("취소할 강의번호: ");
            if (offeringId == null) {
                return;
            }
            CancelEnrollmentResponse response = enrollment.cancel(offeringId);
            view.println("[취소 완료] " + response.getMessage() + " (강의번호 " + response.getOfferingId() + ")");
        } catch (StatusRuntimeException e) {
            view.printError(e);
            checkPassExpired(e);
        }
    }

    private void showMyEnrollments() {
        view.println("[내 신청 목록]");
        if (!waitForAdmission()) {
            return;
        }

        try {
            ListMyEnrollmentsResponse mine = enrollment.listMyEnrollments();
            view.printOfferings(mine.getOfferingsList());
            view.println("신청 학점: " + mine.getTotalCredits() + " / " + mine.getMaxCredits());
        } catch (StatusRuntimeException e) {
            view.printError(e);
            checkPassExpired(e);
        }
    }

    private boolean waitForAdmission() {
        QueueStatusResponse status = enrollment.getQueueStatus();
        if (status.getState() == QueueState.ADMITTED) {
            return true;
        }

        status = enrollment.enterQueue();
        if (status.getState() == QueueState.ADMITTED) {
            printAdmitted(status);
            return true;
        }

        view.println("대기열에 들어갔습니다. 순서가 되면 자동으로 입장합니다.");
        int lastAhead = -1;
        if (status.getState() == QueueState.WAITING) {
            lastAhead = printWaiting(status, lastAhead);
        }

        Iterator<QueueStatusResponse> updates = enrollment.watchQueue();
        while (updates.hasNext()) {
            QueueStatusResponse update = updates.next();
            if (update.getState() == QueueState.ADMITTED) {
                printAdmitted(update);
                return true;
            }
            if (update.getState() != QueueState.WAITING) {
                break;
            }
            lastAhead = printWaiting(update, lastAhead);
        }

        view.println("대기열에서 빠졌습니다. 다시 시도해 주세요.");
        return false;
    }

    private int printWaiting(QueueStatusResponse status, int lastAhead) {
        int ahead = Math.max(status.getPosition() - 1, 0);
        if (ahead != lastAhead) {
            view.println("앞에 " + ahead + "명 대기 중...");
        }
        return ahead;
    }

    private void printAdmitted(QueueStatusResponse status) {
        view.println("[입장] " + LocalTime.now().format(TIME_FORMAT) + " 입장, 입장권 유효 시간 "
                + status.getPassRemainingSeconds() + "초");
    }

    private boolean checkPassExpired(StatusRuntimeException e) {
        if (e.getStatus().getCode() != Status.Code.FAILED_PRECONDITION) {
            return false;
        }
        try {
            if (enrollment.getQueueStatus().getState() == QueueState.ADMITTED) {
                return false;
            }
        } catch (StatusRuntimeException ignored) {
        }
        view.println("입장권이 만료되었습니다. 메뉴로 돌아갑니다. 수강신청을 다시 선택하면 대기열에 다시 들어갑니다.");
        return true;
    }
}
