package com.serverarchitecture.academic;

public record Offering(int offeringId, String courseId, String semester, String section, String professorId,
                       int capacity, String room, String times) {
}
