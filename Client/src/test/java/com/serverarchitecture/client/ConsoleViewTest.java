package com.serverarchitecture.client;

import com.serverarchitecture.OfferingTime;
import io.grpc.Status;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConsoleViewTest {

    private static ConsoleView view(String input, ByteArrayOutputStream out) {
        return new ConsoleView(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),
                new PrintStream(out, true, StandardCharsets.UTF_8));
    }

    @Test
    void parsesNumbers() {
        assertEquals(8, ConsoleView.parseNumber("8"));
        assertEquals(0, ConsoleView.parseNumber("0"));
        assertNull(ConsoleView.parseNumber(""));
        assertNull(ConsoleView.parseNumber("abc"));
        assertNull(ConsoleView.parseNumber("-1"));
        assertNull(ConsoleView.parseNumber("1.5"));
        assertNull(ConsoleView.parseNumber("99999999999"));
    }

    @Test
    void parsesLists() {
        assertEquals(List.of(), ConsoleView.parseList("  "));
        assertEquals(List.of("CS101", "CS201", "CS301"), ConsoleView.parseList("CS101, CS201  CS301,"));
    }

    @Test
    void explainsInvalidMenuChoice() {
        assertEquals("메뉴 번호를 입력해 주세요.", ConsoleView.invalidChoiceMessage("", 6));
        assertEquals("숫자(1~6) 또는 X를 입력해 주세요.", ConsoleView.invalidChoiceMessage("abc", 6));
        assertEquals("없는 메뉴 번호입니다. 1~9 또는 X를 입력해 주세요.", ConsoleView.invalidChoiceMessage("10", 9));
    }

    @Test
    void describesErrors() {
        assertEquals("정원이 마감되었습니다.",
                ConsoleView.describe(Status.FAILED_PRECONDITION.withDescription("정원이 마감되었습니다.")));
        assertEquals("서버에 연결할 수 없습니다.", ConsoleView.describe(Status.UNAVAILABLE));
        assertEquals("서버 응답 시간이 초과되었습니다. (deadline exceeded after 10s)",
                ConsoleView.describe(Status.DEADLINE_EXCEEDED.withDescription("deadline exceeded after 10s")));
        assertEquals("요청을 처리하지 못했습니다. INTERNAL", ConsoleView.describe(Status.INTERNAL));
    }

    @Test
    void measuresKoreanAsDoubleWidth() {
        assertEquals(6, ConsoleView.displayWidth("학생ab"));
        assertEquals("자료  ", ConsoleView.pad("자료", 6));
    }

    @Test
    void alignsTableColumns() {
        List<String> lines = ConsoleView.formatTable(
                new String[]{"번호", "과목명"},
                List.of(new String[]{"8", "자료구조"}, new String[]{"10", "OS"}));

        assertEquals(4, lines.size());
        assertEquals("번호 | 과목명", lines.get(0));
        assertEquals("-----+---------", lines.get(1));
        assertEquals("8    | 자료구조", lines.get(2));
        assertEquals("10   | OS", lines.get(3));
    }

    @Test
    void formatsTimesWithKoreanDays() {
        assertEquals("월 09:00-10:30, 수 13:00-14:30", ConsoleView.formatTimes(List.of(
                OfferingTime.newBuilder().setDayOfWeek("MON").setStartTime("09:00").setEndTime("10:30").build(),
                OfferingTime.newBuilder().setDayOfWeek("WED").setStartTime("13:00").setEndTime("14:30").build())));
        assertEquals("-", ConsoleView.formatTimes(List.of()));
    }

    @Test
    void readsInputAndSignalsEnd() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ConsoleView view = view("  42 \n\nabc\n\n7\n", out);

        assertEquals(42, view.readNumber("번호: "));
        assertNull(view.readNumber("번호: "));
        assertNull(view.readNumber("번호: "));
        assertEquals(0, view.readOptionalNumber("학점: "));
        assertEquals(7, view.readOptionalNumber("학점: "));
        assertThrows(InputClosedException.class, () -> view.readLine("다음: "));

        String printed = out.toString(StandardCharsets.UTF_8);
        assertTrue(printed.contains("값을 입력해 주세요."));
        assertTrue(printed.contains("0 이상의 숫자로 입력해 주세요."));
    }
}
