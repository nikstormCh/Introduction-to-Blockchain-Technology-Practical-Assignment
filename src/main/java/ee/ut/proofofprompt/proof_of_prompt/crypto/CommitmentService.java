package ee.ut.proofofprompt.proof_of_prompt.crypto;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

public final class CommitmentService {
    private final SecureRandom random = new SecureRandom();

    /** Call only with the final ordered input assembled by the service. */
    public CommittedRequest commit(List<InputMessage> input) {
        List<InputMessage> copy = List.copyOf(input);
        if (copy.isEmpty()) throw new IllegalArgumentException("Empty request");

        List<PromptMessage> messages = new ArrayList<>();
        for (int i = 0; i < copy.size(); i++) {
            // Allocate 32 bytes. They are initially zero, not yet a random secret.
            byte[] secret = new byte[32];

            // Generate a fresh cryptographically strong secret for this message.
            // A later verification must reuse these exact bytes, not generate new ones.
            random.nextBytes(secret);

            // Read the original role and text; this does not generate or change the secret.
            InputMessage item = copy.get(i);

            // Encode the same 32 bytes as 64 lowercase hexadecimal characters.
            // Hex is reversible encoding, not encryption or hashing. Keep it private.
            String secretHex = HexFormat.of().formatHex(secret);

            // The list index becomes the position; version 1 selects our evidence rules.
            messages.add(new PromptMessage(
                    1, i, item.role(), item.content(), secretHex));
        }
        return new CommittedRequest(messages);
    }
}
