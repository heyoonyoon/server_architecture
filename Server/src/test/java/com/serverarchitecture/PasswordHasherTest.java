package com.serverarchitecture;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PasswordHasherTest {
    @Test
    void matchesOnlySamePassword() {
        String salt = PasswordHasher.newSalt();
        String hash = PasswordHasher.hash("20100124", salt);

        assertTrue(PasswordHasher.matches("20100124", hash, salt));
        assertFalse(PasswordHasher.matches("20100125", hash, salt));
    }

    @Test
    void differentSaltGivesDifferentHash() {
        assertNotEquals(PasswordHasher.hash("P001", PasswordHasher.newSalt()),
                PasswordHasher.hash("P001", PasswordHasher.newSalt()));
    }
}
