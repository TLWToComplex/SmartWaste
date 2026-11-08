package com.example.shizhan.rubbish.utils;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public final class PasswordUtils {
    public static void validateNewPassword(String raw) {
        if (raw == null || raw.length() < 8 || raw.getBytes(StandardCharsets.UTF_8).length > 72)
            throw new com.example.shizhan.rubbish.common.BusinessException("密码至少8位，且不超过72字节");
    }
    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder();
    private PasswordUtils() { }
    public static String encode(String raw) { return ENCODER.encode(raw); }
    public static boolean isEncoded(String value) { return value != null && value.startsWith("$2"); }
    public static boolean matches(String raw, String stored) {
        if (raw == null || stored == null) return false;
        if (isEncoded(stored)) return ENCODER.matches(raw, stored);
        return MessageDigest.isEqual(raw.getBytes(StandardCharsets.UTF_8), stored.getBytes(StandardCharsets.UTF_8));
    }
}
