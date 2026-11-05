package ee.ut.proofofprompt.proof_of_prompt.crypto;

import java.util.ArrayList;
import java.util.List;

/** Immutable request snapshot and the tree built from its messages. */
public final class CommittedRequest {
    private final List<PromptMessage> promptMessages;
    private final MerkleTree tree;
    CommittedRequest(List<PromptMessage> promptMessages) {
        this.promptMessages = List.copyOf(promptMessages);

        List<String> listOfCommitmentHashes = new ArrayList<>();
        for (PromptMessage message : this.promptMessages){
            String hash = message.commitmentHash();
            listOfCommitmentHashes.add(hash);
        }

        this.tree = new MerkleTree(listOfCommitmentHashes);
    }
    public String merkleRoot() { return tree.rootHash(); }
    public int messageCount() { return promptMessages.size(); }
    /** Private state: future persistence layer must retain these exact secrets. */
    public List<PromptMessage> messages() { return promptMessages; }
    public Disclosure disclose(int position) {
        return new Disclosure(promptMessages.get(position), tree.proof(position));
    }
}
