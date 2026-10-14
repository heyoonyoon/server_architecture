package com.serverarchitecture;

import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnrollmentRulesTest {
    private static Offering offering(int id, String courseId, int credits, List<String> prerequisites, TimeSlot... times) {
        return new Offering(id, courseId, courseId, credits, "2026-2", "01", "P001", "Park", "Y5401", 30, 0,
                List.of(times), prerequisites);
    }

    private static TimeSlot slot(String day, String start, String end) {
        return new TimeSlot(day, LocalTime.parse(start), LocalTime.parse(end));
    }

    @Test
    void overlappingSlotsOnSameDayConflict() {
        Offering java = offering(1, "12345", 3, List.of(), slot("MON", "09:00", "10:30"), slot("WED", "09:00", "10:30"));
        Offering architecture = offering(7, "17655", 3, List.of(), slot("WED", "09:00", "10:30"));

        assertEquals(java, EnrollmentRules.findTimeConflict(architecture, List.of(java)));
    }

    @Test
    void adjacentSlotsDoNotConflict() {
        Offering java = offering(1, "12345", 3, List.of(), slot("MON", "09:00", "10:30"));
        Offering cpp = offering(2, "23456", 3, List.of(), slot("MON", "10:30", "12:00"));
        Offering other = offering(3, "17654", 3, List.of(), slot("TUE", "09:00", "10:30"));

        assertNull(EnrollmentRules.findTimeConflict(cpp, List.of(java, other)));
    }

    @Test
    void sameOfferingIsIgnored() {
        Offering java = offering(1, "12345", 3, List.of(), slot("MON", "09:00", "10:30"));

        assertNull(EnrollmentRules.findTimeConflict(java, List.of(java)));
        assertFalse(EnrollmentRules.hasSameCourse(java, List.of(java)));
        assertFalse(EnrollmentRules.exceedsCredits(java, List.of(java), 3));
    }

    @Test
    void missingPrerequisitesKeepOrder() {
        Offering architecture = offering(7, "17655", 3, List.of("12345", "17651"));

        assertEquals(List.of("12345", "17651"), EnrollmentRules.missingPrerequisites(architecture, Set.of()));
        assertEquals(List.of("17651"), EnrollmentRules.missingPrerequisites(architecture, Set.of("12345")));
        assertTrue(EnrollmentRules.missingPrerequisites(architecture, Set.of("12345", "17651")).isEmpty());
    }

    @Test
    void otherSectionOfSameCourseIsDetected() {
        Offering first = offering(5, "17653", 3, List.of());
        Offering second = offering(8, "17653", 3, List.of());

        assertTrue(EnrollmentRules.hasSameCourse(second, List.of(first)));
    }

    @Test
    void creditsOverMaximumAreRejected() {
        Offering a = offering(1, "12345", 9, List.of());
        Offering b = offering(2, "23456", 9, List.of());
        Offering c = offering(3, "17651", 3, List.of());

        assertFalse(EnrollmentRules.exceedsCredits(b, List.of(a), 18));
        assertTrue(EnrollmentRules.exceedsCredits(c, List.of(a, b), 18));
        assertEquals(21, EnrollmentRules.totalCredits(List.of(a, b, c)));
    }
}
