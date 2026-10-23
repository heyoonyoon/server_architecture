package com.serverarchitecture.academic;

import com.serverarchitecture.DataClient;
import com.serverarchitecture.DataCollections;
import com.serverarchitecture.data.api.Item;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class EnrollmentRepository {
    private static final String ENROLLED = "ENROLLED";

    private final DataClient data;

    public EnrollmentRepository(DataClient data) {
        this.data = data;
    }

    public List<Integer> enrolledOfferingIds(String studentId) {
        return data.readWhere(DataCollections.ENROLLMENTS, "student_id", studentId).stream()
                .filter(EnrollmentRepository::isEnrolled)
                .map(item -> Integer.parseInt(item.getFieldsOrDefault("offering_id", "0")))
                .toList();
    }

    public List<String> enrolledStudentIds(int offeringId) {
        return data.readWhere(DataCollections.ENROLLMENTS, "offering_id", String.valueOf(offeringId)).stream()
                .filter(EnrollmentRepository::isEnrolled)
                .map(item -> item.getFieldsOrDefault("student_id", ""))
                .toList();
    }

    public Map<Integer, Integer> enrolledCounts(String semester) {
        Map<Integer, Integer> counts = new HashMap<Integer, Integer>();
        for (Item item : data.readWhere(DataCollections.ENROLLMENTS, "semester", semester)) {
            if (isEnrolled(item)) {
                counts.merge(Integer.parseInt(item.getFieldsOrDefault("offering_id", "0")), 1, Integer::sum);
            }
        }
        return counts;
    }

    private static boolean isEnrolled(Item item) {
        return ENROLLED.equals(item.getFieldsOrDefault("status", ""));
    }
}
