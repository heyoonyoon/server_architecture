package com.serverarchitecture;

import com.serverarchitecture.data.api.Item;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SeedLoaderTest {
    @Test
    void everySeedPasswordIsItsUserId() {
        List<Item> users = SeedLoader.read(DataCollections.USERS);

        assertEquals(23, users.size());
        for (Item user : users) {
            assertTrue(PasswordHasher.matches(user.getId(), user.getFieldsOrThrow("password_hash")), user.getId());
        }
    }

    @Test
    void readsOfferingsWithTimes() {
        List<Item> offerings = SeedLoader.read(DataCollections.OFFERINGS);

        assertEquals(8, offerings.size());
        assertEquals("MON 09:00-10:30,WED 09:00-10:30", offerings.get(0).getFieldsOrThrow("times"));
    }

    @Test
    void keepsEmptyTrailingField() {
        Item presenter = SeedLoader.read(DataCollections.STUDENTS).stream()
                .filter(item -> item.getId().equals("60220435"))
                .findFirst()
                .orElseThrow();

        assertEquals("", presenter.getFieldsOrThrow("completed_course_ids"));
    }

    @Test
    void loadTestUsersShareOnePasswordHash() {
        List<Item> users = SeedLoader.loadTestItems(DataCollections.USERS);

        assertEquals(3660, users.size());
        assertEquals("30000001", users.get(0).getId());
        assertEquals("30003660", users.get(users.size() - 1).getId());
        String passwordHash = users.get(0).getFieldsOrThrow("password_hash");
        assertTrue(passwordHash.startsWith("pbkdf2$100000$"));
        assertTrue(PasswordHasher.matches("loadtest0811", passwordHash));
        for (Item user : users) {
            assertEquals(passwordHash, user.getFieldsOrThrow("password_hash"), user.getId());
            assertEquals("STUDENT", user.getFieldsOrThrow("role"));
        }
    }

    @Test
    void loadTestStudentsHaveNoCompletedCourses() {
        List<Item> students = SeedLoader.loadTestItems(DataCollections.STUDENTS);

        assertEquals(3660, students.size());
        for (Item student : students) {
            assertEquals("", student.getFieldsOrThrow("completed_course_ids"));
            assertEquals("18", student.getFieldsOrThrow("max_credits"));
        }
    }

    @Test
    void loadTestOfferingsFillUpBeforeEveryStudentGetsASeat() {
        List<Item> courses = SeedLoader.loadTestItems(DataCollections.COURSES);
        List<Item> offerings = SeedLoader.loadTestItems(DataCollections.OFFERINGS);

        assertEquals(5, courses.size());
        for (Item course : courses) {
            assertEquals("", course.getFieldsOrThrow("prerequisite_course_ids"));
        }
        assertEquals(10, offerings.size());
        int capacity = offerings.stream().mapToInt(item -> Integer.parseInt(item.getFieldsOrThrow("capacity"))).sum();
        assertEquals(3000, capacity);
        assertTrue(capacity < SeedLoader.LOADTEST_STUDENT_COUNT);
    }

    @Test
    void loadTestOfferingsDoNotOverlapAnyOffering() {
        List<Item> offerings = new ArrayList<Item>(SeedLoader.read(DataCollections.OFFERINGS));
        offerings.addAll(SeedLoader.loadTestItems(DataCollections.OFFERINGS));

        for (Item load : SeedLoader.loadTestItems(DataCollections.OFFERINGS)) {
            for (Item other : offerings) {
                if (!other.getId().equals(load.getId())) {
                    assertFalse(overlaps(load.getFieldsOrThrow("times"), other.getFieldsOrThrow("times")),
                            load.getId() + " " + other.getId());
                }
            }
        }
    }

    private static boolean overlaps(String a, String b) {
        for (String slotA : a.split(",")) {
            for (String slotB : b.split(",")) {
                String[] partsA = slotA.split("[ -]");
                String[] partsB = slotB.split("[ -]");
                if (partsA[0].equals(partsB[0])
                        && LocalTime.parse(partsA[1]).isBefore(LocalTime.parse(partsB[2]))
                        && LocalTime.parse(partsB[1]).isBefore(LocalTime.parse(partsA[2]))) {
                    return true;
                }
            }
        }
        return false;
    }
}
