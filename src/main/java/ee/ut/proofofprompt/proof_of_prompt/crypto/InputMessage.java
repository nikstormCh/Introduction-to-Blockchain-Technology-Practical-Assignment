package ee.ut.proofofprompt.proof_of_prompt.crypto;

import java.util.Objects;

/** Role and text before a position and secret are assigned. */
public record InputMessage(MessageRole role, String content) {
    public InputMessage {
        Objects.requireNonNull(role, "role");
        CanonicalMessage.validateUnicode(Objects.requireNonNull(content, "content"));
    }
    @Override public String toString() { return "InputMessage[role=" + role + ", content=<redacted>]"; }
}
