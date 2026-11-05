package ee.ut.proofofprompt.proof_of_prompt.crypto;

/** Internal tree node. A leaf has no children; count is the number of leaves below it. */
record MerkleNode(String hash, int count, MerkleNode left, MerkleNode right) {}
