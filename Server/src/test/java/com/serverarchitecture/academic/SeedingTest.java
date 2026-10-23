package com.serverarchitecture.academic;

import com.serverarchitecture.DataClient;
import com.serverarchitecture.DataCollections;
import com.serverarchitecture.SeedLoader;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class SeedingTest {
    @Test
    void seedsEveryCollectionOnceEvenWhenRunTwice() throws Exception {
        try (FakeDataService fake = new FakeDataService()) {
            DataClient data = fake.client();
            String[] collections = {DataCollections.USERS, DataCollections.STUDENTS,
                    DataCollections.COURSES, DataCollections.OFFERINGS};

            SeedLoader.seedWithRetry(data, collections);
            SeedLoader.seedWithRetry(data, collections);

            assertEquals(23, data.count(DataCollections.USERS));
            assertEquals(18, data.count(DataCollections.STUDENTS));
            assertEquals(7, data.count(DataCollections.COURSES));
            assertEquals(8, data.count(DataCollections.OFFERINGS));
            assertEquals(0, data.count(DataCollections.ENROLLMENTS));
        }
    }

    @Test
    void doesNotRestoreDeletedSeedItemsOnRestart() throws Exception {
        try (FakeDataService fake = new FakeDataService()) {
            DataClient data = fake.client();
            SeedLoader.seedWithRetry(data, DataCollections.STUDENTS);
            data.delete(DataCollections.STUDENTS, "60220435");

            SeedLoader.seedWithRetry(data, DataCollections.STUDENTS);

            assertEquals(17, data.count(DataCollections.STUDENTS));
            assertFalse(data.read(DataCollections.STUDENTS, "60220435").isPresent());
        }
    }
}
