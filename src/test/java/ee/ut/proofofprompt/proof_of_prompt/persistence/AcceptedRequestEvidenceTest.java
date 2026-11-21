package ee.ut.proofofprompt.proof_of_prompt.persistence;

import ee.ut.proofofprompt.proof_of_prompt.crypto.CommitmentService;
import ee.ut.proofofprompt.proof_of_prompt.crypto.CommittedRequest;
import ee.ut.proofofprompt.proof_of_prompt.crypto.InputMessage;
import ee.ut.proofofprompt.proof_of_prompt.crypto.MessageRole;
import ee.ut.proofofprompt.proof_of_prompt.crypto.PromptMessage;
import ee.ut.proofofprompt.proof_of_prompt.crypto.RequestAcceptanceReceiptIssuer;
import org.junit.jupiter.api.Test;

import java.security.KeyPairGenerator;
import java.time.Clock;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AcceptedRequestEvidenceTest {
    @Test
    void restoreRequiresOriginalPositionsAndMessageContents() throws Exception {
        var original = new CommitmentService().commit(List.of(
                new InputMessage(MessageRole.SYSTEM, "Instructions"),
                new InputMessage(MessageRole.USER, "Question")));
        var signingKey = KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPrivate();
        var receipt = new RequestAcceptanceReceiptIssuer(signingKey, "test-key", Clock.systemUTC())
                .issueSignedReceipt(original, "test-model");
        var id = original.acceptedRequestId();
        var originalMessages = original.messages();

        var restored = CommittedRequest.restore(id, originalMessages);
        assertEquals(original.disclose(1), new AcceptedRequestEvidence(restored, receipt).committedRequest().disclose(1));
        assertThrows(IllegalArgumentException.class, () -> CommittedRequest.restore(id, originalMessages.reversed()));
        var truncated = CommittedRequest.restore(id, List.of(originalMessages.getFirst()));
        assertThrows(IllegalArgumentException.class, () -> new AcceptedRequestEvidence(truncated, receipt));
        var changed = new PromptMessage(1, 1, MessageRole.USER, "Changed", originalMessages.getLast().randomValue());
        var altered = CommittedRequest.restore(id, List.of(originalMessages.getFirst(), changed));
        assertThrows(IllegalArgumentException.class, () -> new AcceptedRequestEvidence(altered, receipt));
    }
}
