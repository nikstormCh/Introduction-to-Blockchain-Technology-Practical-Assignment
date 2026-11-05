package ee.ut.proofofprompt.proof_of_prompt.crypto;

import java.util.ArrayList;
import java.util.List;
import static ee.ut.proofofprompt.proof_of_prompt.crypto.SiblingSide.*;

/** Ordered RFC 9162 tree shape; no sorting and no duplicated odd leaves. */
public final class MerkleTree {
    private final MerkleNode root;

    public MerkleTree(List<String> leaves) {
        List<String> copy = List.copyOf(leaves);
        if (copy.isEmpty()) throw new IllegalArgumentException("Empty request");
        copy.forEach(Hashing::requireHash);
        root = build(copy, 0, copy.size());
    }

    public String rootHash() { return root.hash(); }
    public int messageCount() { return root.count(); }

    private static MerkleNode build(List<String> leaves, int from, int count) {
        if (count == 1) return new MerkleNode(leaves.get(from), 1, null, null);
        int k = Integer.highestOneBit(count - 1);
        MerkleNode left = build(leaves, from, k);
        MerkleNode right = build(leaves, from + k, count - k);
        return new MerkleNode(Hashing.node(left.hash(), right.hash()), count, left, right);
    }

    public MerkleProof proof(int position) {
        if (position < 0 || position >= root.count()) throw new IllegalArgumentException("Position outside tree");
        List<ProofStep> steps = new ArrayList<>();
        collect(root, position, steps);
        return new MerkleProof(steps);
    }

    private static void collect(MerkleNode node, int position, List<ProofStep> steps) {
        if (node.count() == 1) return;
        if (position < node.left().count()) {
            collect(node.left(), position, steps);
            steps.add(new ProofStep(node.right().hash(), RIGHT));
        } else {
            collect(node.right(), position - node.left().count(), steps);
            steps.add(new ProofStep(node.left().hash(), LEFT));
        }
    }

    /** Only inclusion, NOT complete Proof-of-Prompt verification.
     * expectedRoot and expectedCount must come from independently fixed context.
     */
    public static boolean verify(PromptMessage message, MerkleProof proof,
                                 String expectedRoot, int expectedCount) {
        if (message == null || proof == null || expectedCount < 1 || message.position() >= expectedCount)
            return false;
        try { Hashing.requireHash(expectedRoot); }
        catch (IllegalArgumentException e) { return false; }
        List<SiblingSide> expectedSides = new ArrayList<>();
        expectedSides(message.position(), expectedCount, expectedSides);
        if (proof.steps().size() != expectedSides.size()) return false;
        String current = message.commitmentHash();
        for (int i = 0; i < expectedSides.size(); i++) {
            ProofStep step = proof.steps().get(i);
            if (step.side() != expectedSides.get(i)) return false;
            current = step.side() == LEFT ? Hashing.node(step.hash(), current) : Hashing.node(current, step.hash());
        }
        return current.equals(expectedRoot);
    }

    private static void expectedSides(int position, int count, List<SiblingSide> sides) {
        if (count == 1) return;
        int k = Integer.highestOneBit(count - 1);
        if (position < k) {
            expectedSides(position, k, sides);
            sides.add(RIGHT);
        } else {
            expectedSides(position - k, count - k, sides);
            sides.add(LEFT);
        }
    }
}
