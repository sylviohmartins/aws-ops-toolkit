package io.github.awsopstoolkit.security;

public final class Masking {
    private static final int STABLE_ID_PREFIX_LENGTH = 16;

    private Masking() {}

    public static String stableIdentifier(String value) {
        if (value == null || value.isBlank()) return "";
        return Hashing.sha256Hex(value).substring(0, STABLE_ID_PREFIX_LENGTH);
    }
}
