package com.serverarchitecture;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PasswordHasherTest {
    @Test
    void matchesOnlySamePassword() {
        String hash = PasswordHasher.hash("20100124");

        assertTrue(PasswordHasher.matches("20100124", hash));
        assertFalse(PasswordHasher.matches("20100125", hash));
    }

    @Test
    void samePasswordGivesDifferentHash() {
        assertNotEquals(PasswordHasher.hash("P001"), PasswordHasher.hash("P001"));
    }

    @Test
    void hashIsBcryptString() {
        assertTrue(PasswordHasher.hash("P001").startsWith("$2"));
    }
}
