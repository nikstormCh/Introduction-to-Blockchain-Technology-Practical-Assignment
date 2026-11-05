package ee.ut.proofofprompt.proof_of_prompt.crypto;

import java.util.List;

/** Immutable proof steps ordered from the selected leaf toward the root. */
public record MerkleProof(List<ProofStep> steps) {
    public MerkleProof {
        steps = List.copyOf(steps);
    }
}
