package ee.ut.proofofprompt.proof_of_prompt.crypto;

import java.nio.charset.StandardCharsets;

/** RFC 8785 encoding for the FIXED message schema, not a general JSON library. */
public final class CanonicalMessage {
    private CanonicalMessage() {}

    public static byte[] encode(PromptMessage message) {
        // Keys are in UTF-16 lexicographic order; integers fit signed Java int.
        String json = "{\"content\":" + quote(message.content())
                + ",\"position\":" + message.position()
                + ",\"protocolVersion\":" + message.protocolVersion()
                + ",\"randomValue\":" + quote(message.randomValue())
                + ",\"role\":" + quote(message.role().name()) + "}";
        return json.getBytes(StandardCharsets.UTF_8);
    }

    static void validateUnicode(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i == text.length() || !Character.isLowSurrogate(text.charAt(i)))
                    throw new IllegalArgumentException("Unpaired Unicode surrogate");
            } else if (Character.isLowSurrogate(c)) {
                throw new IllegalArgumentException("Unpaired Unicode surrogate");
            }
        }
    }

    static String quote(String text) {
        validateUnicode(text);
        StringBuilder out = new StringBuilder("\"");
        String hex = "0123456789abcdef";
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) out.append("\\u00").append(hex.charAt(c >> 4)).append(hex.charAt(c & 15));
                    else out.append(c);
                }
            }
        }
        return out.append('"').toString();
    }
}
