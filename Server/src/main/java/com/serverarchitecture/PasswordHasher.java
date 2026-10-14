package com.serverarchitecture;

import at.favre.lib.crypto.bcrypt.BCrypt;
import at.favre.lib.crypto.bcrypt.LongPasswordStrategies;

public class PasswordHasher {
    private static final int COST = 10;
    private static final BCrypt.Version VERSION = BCrypt.Version.VERSION_2A;
    private static final BCrypt.Hasher HASHER = BCrypt.with(VERSION, LongPasswordStrategies.truncate(VERSION));
    private static final BCrypt.Verifyer VERIFYER = BCrypt.verifyer(VERSION, LongPasswordStrategies.truncate(VERSION));

    public static String hash(String password) {
        return HASHER.hashToString(COST, password.toCharArray());
    }

    public static boolean matches(String password, String stored) {
        return VERIFYER.verify(password.toCharArray(), stored).verified;
    }
}
