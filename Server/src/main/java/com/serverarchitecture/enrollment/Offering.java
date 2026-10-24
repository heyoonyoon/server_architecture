package com.serverarchitecture.enrollment;

import com.serverarchitecture.OfferingInfo;
import com.serverarchitecture.OfferingTime;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

public record Offering(int offeringId, String courseId, String courseName, int credits, String semester,
                       String section, String professorId, String professorName, String room, int capacity,
                       int enrolledCount, List<TimeSlot> times, List<String> prerequisiteCourseIds) {
    public static Offering from(OfferingInfo info) {
        List<TimeSlot> times = new ArrayList<TimeSlot>();
        for (OfferingTime time : info.getTimesList()) {
            times.add(new TimeSlot(time.getDayOfWeek(),
                    LocalTime.parse(time.getStartTime()),
                    LocalTime.parse(time.getEndTime())));
        }
        return new Offering(info.getOfferingId(), info.getCourseId(), info.getCourseName(), info.getCredits(),
                info.getSemester(), info.getSection(), info.getProfessorId(), info.getProfessorName(),
                info.getRoom(), info.getCapacity(), info.getCapacity() - info.getRemaining(),
                List.copyOf(times), List.copyOf(info.getPrerequisiteCourseIdsList()));
    }
}
