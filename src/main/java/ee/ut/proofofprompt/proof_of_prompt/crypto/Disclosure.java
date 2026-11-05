package ee.ut.proofofprompt.proof_of_prompt.crypto;

import java.util.Objects;

/** One selected message and its path; other messages and secrets are excluded. */
public record Disclosure(PromptMessage message, MerkleProof proof) {
    public Disclosure {
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(proof, "proof");
    }
}
