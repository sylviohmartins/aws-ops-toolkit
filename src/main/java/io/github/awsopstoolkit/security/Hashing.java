package io.github.awsopstoolkit.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class Hashing {
    private Hashing() {}

    public static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the JDK", impossible);
        }
    }

    public static String sha256Hex(String value) {
        return HexFormat.of()
                .formatHex(sha256Digest().digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
