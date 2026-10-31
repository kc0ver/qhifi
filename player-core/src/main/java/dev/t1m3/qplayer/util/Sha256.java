package dev.t1m3.qplayer.util;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Locale;

/** SHA-256 helpers for downloaded release assets (fresh downloads and cached copies). */
public final class Sha256 {
    private Sha256() {}

    /** Lowercase hex digest of {@code file}, or "" when it is missing/unreadable. */
    public static String of(File file) {
        if (file == null || !file.isFile()) return "";
        try (InputStream in = new FileInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[16384];
            int n;
            while ((n = in.read(buffer)) > 0) digest.update(buffer, 0, n);
            return hex(digest.digest());
        } catch (Exception unreadable) {
            return "";
        }
    }

    /** True when the file's digest matches {@code expectedHex} (case-insensitive).
     *  An empty expectation never matches: callers decide how to fall back. */
    public static boolean matches(File file, String expectedHex) {
        String expected = expectedHex == null ? "" : expectedHex.trim().toLowerCase(Locale.ROOT);
        return !expected.isEmpty() && of(file).equals(expected);
    }

    private static String hex(byte[] bytes) {
        StringBuilder text = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            text.append(Character.forDigit((b >> 4) & 0xF, 16));
            text.append(Character.forDigit(b & 0xF, 16));
        }
        return text.toString();
    }
}
