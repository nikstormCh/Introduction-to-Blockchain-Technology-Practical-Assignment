package ee.ut.proofofprompt.proof_of_prompt.persistence;

import ee.ut.proofofprompt.proof_of_prompt.crypto.CommittedRequest;
import ee.ut.proofofprompt.proof_of_prompt.crypto.SignedRequestAcceptanceReceipt;

/** A private snapshot. Only a selected disclosure and its receipt may leave the service. */
public record AcceptedRequestEvidence(CommittedRequest committedRequest, SignedRequestAcceptanceReceipt signedReceipt) {
    public AcceptedRequestEvidence {
        var receiptBody = signedReceipt.receiptBody();
        if (!receiptBody.recordsAcceptanceOf(committedRequest.acceptedRequestId())
                || receiptBody.messageCountInRequest() != committedRequest.messageCount()
                || !receiptBody.requestMessagesMerkleRoot().equals(committedRequest.merkleRoot())) {
            throw new IllegalArgumentException("Receipt does not match the committed request");
        }
    }
}
