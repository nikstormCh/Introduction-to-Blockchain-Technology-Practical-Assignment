package ee.ut.proofofprompt.proof_of_prompt.crypto;

import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.Signature;
import java.time.Clock;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;

public final class RequestAcceptanceReceiptIssuer {
    private final PrivateKey serviceSigningPrivateKey;
    private final String receiptSigningKeyId;
    private final Clock acceptanceClock;

    public RequestAcceptanceReceiptIssuer(PrivateKey serviceSigningPrivateKey, String receiptSigningKeyId,
                                          Clock acceptanceClock) {
        this.serviceSigningPrivateKey = Objects.requireNonNull(serviceSigningPrivateKey, "serviceSigningPrivateKey");
        RequestAcceptanceReceiptBody.requireNonBlankIdentifier(receiptSigningKeyId, "receiptSigningKeyId");
        this.receiptSigningKeyId = receiptSigningKeyId;
        this.acceptanceClock = Objects.requireNonNull(acceptanceClock, "acceptanceClock");
    }

    public SignedRequestAcceptanceReceipt issueSignedReceipt(CommittedRequest committedRequest, String selectedModelId)
            throws GeneralSecurityException {
        RequestAcceptanceReceiptBody receiptBody = new RequestAcceptanceReceiptBody(
                1, UUID.randomUUID(), committedRequest.acceptedRequestId(), selectedModelId,
                committedRequest.merkleRoot(), committedRequest.messageCount(),
                RequestAcceptanceStatus.ACCEPTED, acceptanceClock.instant(), receiptSigningKeyId);

        Signature receiptSigner = Signature.getInstance("Ed25519");
        receiptSigner.initSign(serviceSigningPrivateKey);
        receiptSigner.update(CanonicalReceiptEncoder.encodeReceiptBody(receiptBody));
        String receiptSignatureBase64 = Base64.getEncoder().encodeToString(receiptSigner.sign());
        return new SignedRequestAcceptanceReceipt(receiptBody, receiptSignatureBase64);
    }
}
