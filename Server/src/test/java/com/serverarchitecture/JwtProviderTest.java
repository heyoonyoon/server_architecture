package com.serverarchitecture;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class JwtProviderTest {
    private static final String SECRET = "test-secret-test-secret-test-secret";
    private static final User USER = new User("P001", "Park \"Jr\"", Role.PROFESSOR, "", "");

    @Test
    void verifiesIssuedToken() {
        JwtProvider jwtProvider = new JwtProvider(SECRET, Duration.ofHours(2));

        assertEquals(new AuthenticatedUser("P001", Role.PROFESSOR), jwtProvider.verify(jwtProvider.issue(USER)));
    }

    @Test
    void rejectsExpiredToken() {
        JwtProvider jwtProvider = new JwtProvider(SECRET, Duration.ofSeconds(-1));

        assertNull(jwtProvider.verify(jwtProvider.issue(USER)));
    }

    @Test
    void rejectsTokenSignedWithOtherSecret() {
        String token = new JwtProvider("other-secret-other-secret-other-secret", Duration.ofHours(2)).issue(USER);

        assertNull(new JwtProvider(SECRET, Duration.ofHours(2)).verify(token));
    }

    @Test
    void rejectsTamperedPayload() {
        JwtProvider jwtProvider = new JwtProvider(SECRET, Duration.ofHours(2));
        String[] parts = jwtProvider.issue(USER).split("\\.");
        String studentToken = new JwtProvider(SECRET, Duration.ofHours(2))
                .issue(new User("20100124", "Kim Yunmi", Role.STUDENT, "", ""));

        assertNull(jwtProvider.verify(parts[0] + "." + studentToken.split("\\.")[1] + "." + parts[2]));
        assertNull(jwtProvider.verify("not-a-token"));
        assertNull(jwtProvider.verify(null));
    }
}
