package com.serverarchitecture;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

public class PasswordHasher {
    private static final int ITERATIONS = 600_000;
    private static final int KEY_LENGTH = 256;
    private static final int SALT_LENGTH = 16;
    private static final SecureRandom RANDOM = new SecureRandom();

    public static String newSalt() {
        byte[] salt = new byte[SALT_LENGTH];
        RANDOM.nextBytes(salt);
        return Base64.getEncoder().encodeToString(salt);
    }

    public static String hash(String password, String salt) {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), Base64.getDecoder().decode(salt), ITERATIONS, KEY_LENGTH);
        try {
            byte[] hash = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            return Base64.getEncoder().encodeToString(hash);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        } finally {
            spec.clearPassword();
        }
    }

    public static boolean matches(String password, String hash, String salt) {
        return MessageDigest.isEqual(
                Base64.getDecoder().decode(hash(password, salt)),
                Base64.getDecoder().decode(hash));
    }
}
