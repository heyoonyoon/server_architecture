package com.serverarchitecture.enrollment;

import java.time.LocalTime;

public record TimeSlot(String dayOfWeek, LocalTime startTime, LocalTime endTime) {
    public boolean overlaps(TimeSlot other) {
        return dayOfWeek.equals(other.dayOfWeek)
                && startTime.isBefore(other.endTime)
                && other.startTime.isBefore(endTime);
    }
}
