package ee.ut.proofofprompt.proof_of_prompt.crypto;

import java.util.Objects;

/** One sibling hash and its position on the path toward the root. */
public record ProofStep(String hash, SiblingSide side) {
    public ProofStep {
        Hashing.requireHash(hash);
        Objects.requireNonNull(side, "side");
    }
}
