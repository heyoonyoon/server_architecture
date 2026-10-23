package com.serverarchitecture;

import com.serverarchitecture.data.api.Item;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
