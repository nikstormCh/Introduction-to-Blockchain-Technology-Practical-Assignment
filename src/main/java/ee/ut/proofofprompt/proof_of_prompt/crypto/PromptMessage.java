package ee.ut.proofofprompt.proof_of_prompt.crypto;

import java.util.Objects;

/** Immutable opening of one commitment. Keep content and randomValue private. */
public record PromptMessage(int protocolVersion, int position, MessageRole role,
                            String content, String randomValue) {

    public PromptMessage {
        if (protocolVersion != 1) throw new IllegalArgumentException("Unsupported protocol version");
        if (position < 0) throw new IllegalArgumentException("Negative position");
        Objects.requireNonNull(role, "role");
        CanonicalMessage.validateUnicode(Objects.requireNonNull(content, "content"));
        Hashing.requireHash(randomValue);
    }

    public String commitmentHash() { return Hashing.leaf(this); }

    // Avoid accidental disclosure through application logs.
    @Override public String toString() {
        return "PromptMessage[position=" + position + ", role=" + role + ", content=<redacted>, randomValue=<redacted>]";
    }
}
