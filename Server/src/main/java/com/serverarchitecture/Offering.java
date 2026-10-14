package com.serverarchitecture;

import java.util.List;

public record Offering(int offeringId, String courseId, String courseName, int credits, String semester,
                       String section, String professorId, String professorName, String room, int capacity,
                       int enrolledCount, List<TimeSlot> times, List<String> prerequisiteCourseIds) {
}
