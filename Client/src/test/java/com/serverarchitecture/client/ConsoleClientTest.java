package com.serverarchitecture.client;

import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConsoleClientTest {
    private final FakeServices.TokenRecorder recorder = new FakeServices.TokenRecorder();
    private final FakeServices.Academic academic = new FakeServices.Academic();
    private final FakeServices.Enrollment enrollment = new FakeServices.Enrollment();
    private Server server;
    private ManagedChannel channel;

    @BeforeEach
    void setUp() throws Exception {
        String name = InProcessServerBuilder.generateName();
        server = InProcessServerBuilder.forName(name)
                .addService(ServerInterceptors.intercept(new FakeServices.Auth(), recorder))
                .addService(ServerInterceptors.intercept(academic, recorder))
                .addService(ServerInterceptors.intercept(enrollment, recorder))
                .build()
                .start();
        channel = InProcessChannelBuilder.forName(name).build();
    }

    @AfterEach
    void tearDown() {
        channel.shutdownNow();
        server.shutdownNow();
    }

    private String session(String... lines) {
        return session(channel, lines);
    }

    private String session(ManagedChannel target, String... lines) {
        String input = lines.length == 0 ? "" : String.join("\n", lines) + "\n";
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8);
        ConsoleClient client = new ConsoleClient(target,
                new ConsoleView(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), out));
        client.run();
        client.close();
        return bytes.toString(StandardCharsets.UTF_8);
    }

    private static int count(String text, String part) {
        int count = 0;
        int index = text.indexOf(part);
        while (index >= 0) {
            count++;
            index = text.indexOf(part, index + part.length());
        }
        return count;
    }

    @Test
    void studentLogsInListsAndEnrollsThroughQueue() {
        String output = session(
                "s1", "wrong",
                "s1", "s1",
                "", "abc", "7",
                "1",
                "4", "x", "99", "9", "8", "",
                "6",
                "x");

        assertTrue(output.contains("[오류] 아이디 또는 비밀번호가 올바르지 않습니다."));
        assertTrue(output.contains("김학생 학생, 환영합니다."));
        assertTrue(output.contains("메뉴 번호를 입력해 주세요."));
        assertTrue(output.contains("숫자(1~6) 또는 X를 입력해 주세요."));
        assertTrue(output.contains("없는 메뉴 번호입니다."));
        assertTrue(output.contains("운영체제"));

        assertEquals(1, count(output, "앞에 2명 대기 중..."));
        assertEquals(1, count(output, "앞에 1명 대기 중..."));
        assertEquals(1, count(output, "앞에 0명 대기 중..."));
        int firstWaiting = output.indexOf("앞에 2명 대기 중...");
        int lastWaiting = output.indexOf("앞에 0명 대기 중...");
        int admitted = output.indexOf("[입장]");
        int table = output.indexOf("잔여/정원");
        assertTrue(firstWaiting < lastWaiting);
        assertTrue(lastWaiting < admitted);
        assertTrue(admitted < table);
        assertTrue(output.contains("입장권 유효 시간 300초"));

        assertTrue(output.contains("강의번호는 숫자로 입력해 주세요."));
        assertTrue(output.contains("[오류] 존재하지 않는 강의입니다."));
        assertTrue(output.contains("[오류] 정원이 마감되었습니다."));
        assertFalse(output.contains("입장권이 만료되었습니다."));
        assertTrue(output.contains("[신청 완료] 신청이 완료되었습니다. 자료구조 (강의번호 8)"));
        assertTrue(output.contains("2/30"));
        assertTrue(output.contains("1/30"));
        assertTrue(output.contains("월 09:00-10:30"));
        assertTrue(output.contains("신청 학점: 3 / 18"));
        assertTrue(output.contains("종료합니다."));
        assertEquals(7, count(output, "===== 학생 메뉴 ====="));

        assertEquals(List.of(8), enrollment.enrolled);
        assertEquals(1, enrollment.leaveCount.get());
        assertTrue(channel.isShutdown());
        for (String call : recorder.calls) {
            if (!call.startsWith("serverarchitecture.AuthService/")) {
                assertTrue(call.endsWith(" Bearer token-s1"), call);
            }
        }
        assertTrue(recorder.calls.contains("serverarchitecture.EnrollmentService/WatchQueue Bearer token-s1"));
    }

    @Test
    void expiredPassReturnsToMenu() {
        enrollment.expireOnEnroll = true;

        String output = session("s1", "s1", "4", "8", "X");

        assertTrue(output.contains("[오류] 입장권이 없거나 만료되었습니다. 대기열에 다시 들어가세요."));
        int expired = output.indexOf("입장권이 만료되었습니다. 메뉴로 돌아갑니다.");
        assertTrue(expired > 0);
        assertTrue(output.indexOf("===== 학생 메뉴 =====", expired) > expired);
        assertEquals(2, count(output, "===== 학생 메뉴 ====="));
        assertTrue(enrollment.enrolled.isEmpty());
        assertTrue(output.contains("종료합니다."));
    }

    @Test
    void studentCancelsEnrollment() {
        String output = session("s1", "s1", "4", "8", "b", "5", "8", "5", "X");

        assertTrue(output.contains("[취소 완료] 신청을 취소했습니다. (강의번호 8)"));
        assertTrue(output.contains("신청한 강의가 없습니다."));
        assertEquals(1, count(output, "[입장]"));
        assertTrue(enrollment.enrolled.isEmpty());
    }

    @Test
    void professorManagesStudents() {
        String output = session(
                "p1", "p1",
                "1",
                "6", "2026001", "새학생", "컴퓨터공학과", "", "CS101, CS201",
                "6", "2026002", "학생2", "경영학과", "많이",
                "7", "nope",
                "3", "",
                "10",
                "X");

        assertTrue(output.contains("이교수 교수님, 환영합니다."));
        assertTrue(output.contains("컴퓨터공학과"));
        assertTrue(output.contains("학생을 추가했습니다. 2026001 새학생 (초기 비밀번호는 학번)"));
        assertTrue(output.contains("0 이상의 숫자로 입력해 주세요."));
        assertTrue(output.contains("[오류] 존재하지 않는 학생입니다."));
        assertTrue(output.contains("값을 입력해 주세요."));
        assertTrue(output.contains("없는 메뉴 번호입니다. 1~9 또는 X를 입력해 주세요."));
        assertTrue(output.contains("종료합니다."));

        assertEquals(1, academic.added.size());
        assertEquals(0, academic.added.get(0).getMaxCredits());
        assertEquals(List.of("CS101", "CS201"), academic.added.get(0).getCompletedCourseIdsList());
        assertEquals(0, enrollment.leaveCount.get());
        for (String call : recorder.calls) {
            if (!call.startsWith("serverarchitecture.AuthService/")) {
                assertTrue(call.endsWith(" Bearer token-p1"), call);
            }
        }
    }

    @Test
    void endOfInputExitsWithoutX() {
        String output = session("s1", "s1", "1");

        assertTrue(output.contains("입력이 끝나 종료합니다."));
        assertTrue(channel.isShutdown());
    }

    @Test
    void unreachableServerIsReported() {
        ManagedChannel missing = InProcessChannelBuilder.forName("missing-" + System.nanoTime()).build();

        String output = session(missing, "s1", "s1");

        assertTrue(output.contains("[오류] 서버에 연결할 수 없습니다."));
        assertTrue(output.contains("입력이 끝나 종료합니다."));
        assertTrue(missing.isShutdown());
    }
}
