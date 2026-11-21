package ee.ut.proofofprompt.proof_of_prompt.crypto;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Immutable request snapshot and the tree built from its messages. */
public final class CommittedRequest {
    private final UUID acceptedRequestId;
    private final List<PromptMessage> promptMessages;
    private final MerkleTree tree;
    CommittedRequest(List<PromptMessage> promptMessages) {
        this(UUID.randomUUID(), promptMessages);
    }

    public static CommittedRequest restore(UUID acceptedRequestId, List<PromptMessage> orderedMessages) {
        return new CommittedRequest(acceptedRequestId, orderedMessages);
    }

    private CommittedRequest(UUID acceptedRequestId, List<PromptMessage> promptMessages) {
        this.acceptedRequestId = Objects.requireNonNull(acceptedRequestId, "acceptedRequestId");
        this.promptMessages = List.copyOf(promptMessages);

        List<String> listOfCommitmentHashes = new ArrayList<>();
        for (PromptMessage message : this.promptMessages) {
            if (message.position() != listOfCommitmentHashes.size()) {
                throw new IllegalArgumentException("Message positions must be consecutive and start at zero");
            }
            String hash = message.commitmentHash();
            listOfCommitmentHashes.add(hash);
        }

        this.tree = new MerkleTree(listOfCommitmentHashes);
    }
    public UUID acceptedRequestId() { return acceptedRequestId; }
    public String merkleRoot() { return tree.rootHash(); }
    public int messageCount() { return promptMessages.size(); }
    /** Private state: future persistence layer must retain these exact secrets. */
    public List<PromptMessage> messages() { return promptMessages; }
    public Disclosure disclose(int position) {
        return new Disclosure(promptMessages.get(position), tree.proof(position));
    }
}
