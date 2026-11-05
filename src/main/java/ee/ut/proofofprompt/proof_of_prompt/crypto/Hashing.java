package ee.ut.proofofprompt.proof_of_prompt.crypto;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class Hashing {
    private Hashing() {}

    public static String leaf(PromptMessage message) {
        return digest((byte) 0, CanonicalMessage.encode(message));
    }

    public static String node(String left, String right) {
        requireHash(left);
        requireHash(right);
        return digest((byte) 1, HexFormat.of().parseHex(left), HexFormat.of().parseHex(right));
    }

    static void requireHash(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Expected exactly 64 lowercase hex characters");
    }

    private static String digest(byte domain, byte[]... parts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(domain);
            for (byte[] part : parts) digest.update(part);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Java runtime lacks SHA-256", e);
        }
    }
}
