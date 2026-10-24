package com.serverarchitecture.enrollment;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

public final class EnrollmentRules {
    private EnrollmentRules() {
    }

    public static List<String> missingPrerequisites(Offering offering, Set<String> completedCourseIds) {
        List<String> missing = new ArrayList<String>();
        for (String courseId : offering.prerequisiteCourseIds()) {
            if (!completedCourseIds.contains(courseId)) {
                missing.add(courseId);
            }
        }
        return missing;
    }

    public static Offering findTimeConflict(Offering offering, Collection<Offering> enrolled) {
        for (Offering other : enrolled) {
            if (other.offeringId() != offering.offeringId() && overlaps(offering, other)) {
                return other;
            }
        }
        return null;
    }

    public static boolean hasSameCourse(Offering offering, Collection<Offering> enrolled) {
        for (Offering other : enrolled) {
            if (other.offeringId() != offering.offeringId() && other.courseId().equals(offering.courseId())) {
                return true;
            }
        }
        return false;
    }

    public static boolean exceedsCredits(Offering offering, Collection<Offering> enrolled, int maxCredits) {
        int total = offering.credits();
        for (Offering other : enrolled) {
            if (other.offeringId() != offering.offeringId()) {
                total += other.credits();
            }
        }
        return total > maxCredits;
    }

    public static int totalCredits(Collection<Offering> offerings) {
        int total = 0;
        for (Offering offering : offerings) {
            total += offering.credits();
        }
        return total;
    }

    private static boolean overlaps(Offering a, Offering b) {
        for (TimeSlot slotA : a.times()) {
            for (TimeSlot slotB : b.times()) {
                if (slotA.overlaps(slotB)) {
                    return true;
                }
            }
        }
        return false;
    }
}
