package com.serverarchitecture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class JwtProvider {
    private static final Logger log = LoggerFactory.getLogger(JwtProvider.class);
    private static final String DEV_SECRET = "serverarchitecture-dev-jwt-secret-change-me";
    private static final String HEADER = encode("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
    private static final Pattern SUBJECT = Pattern.compile("\"sub\":\"([^\"]+)\"");
    private static final Pattern ROLE = Pattern.compile("\"role\":\"([A-Z_]+)\"");
    private static final Pattern EXPIRES_AT = Pattern.compile("\"exp\":(\\d+)");

    private final SecretKeySpec key;
    private final Duration ttl;

    public JwtProvider(String secret, Duration ttl) {
        this.key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        this.ttl = ttl;
    }

    public static JwtProvider fromEnv() {
        String secret = System.getenv("JWT_SECRET");
        if (secret == null || secret.isBlank()) {
            log.warn("JWT_SECRET 이 설정되지 않아 개발용 기본 키를 사용합니다.");
            secret = DEV_SECRET;
        }
        if (secret.length() < 32) {
            throw new IllegalStateException("JWT_SECRET 은 32자 이상이어야 합니다.");
        }
        return new JwtProvider(secret, Duration.ofHours(2));
    }

    public String issue(User user) {
        long now = Instant.now().getEpochSecond();
        String payload = String.format("{\"sub\":\"%s\",\"name\":\"%s\",\"role\":\"%s\",\"iat\":%d,\"exp\":%d}",
                escape(user.getUserId()), escape(user.getName()), user.getRole().name(), now, now + ttl.toSeconds());
        String content = HEADER + "." + encode(payload.getBytes(StandardCharsets.UTF_8));
        return content + "." + encode(sign(content));
    }

    public AuthenticatedUser verify(String token) {
        if (token == null) {
            return null;
        }
        String[] parts = token.split("\\.");
        if (parts.length != 3 || !parts[0].equals(HEADER)) {
            return null;
        }
        try {
            byte[] signature = Base64.getUrlDecoder().decode(parts[2]);
            if (!MessageDigest.isEqual(sign(parts[0] + "." + parts[1]), signature)) {
                return null;
            }
            String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
            Matcher subject = SUBJECT.matcher(payload);
            Matcher role = ROLE.matcher(payload);
            Matcher expiresAt = EXPIRES_AT.matcher(payload);
            if (!subject.find() || !role.find() || !expiresAt.find()
                    || Long.parseLong(expiresAt.group(1)) <= Instant.now().getEpochSecond()) {
                return null;
            }
            return new AuthenticatedUser(subject.group(1), Role.valueOf(role.group(1)));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private byte[] sign(String content) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            return mac.doFinal(content.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String encode(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
