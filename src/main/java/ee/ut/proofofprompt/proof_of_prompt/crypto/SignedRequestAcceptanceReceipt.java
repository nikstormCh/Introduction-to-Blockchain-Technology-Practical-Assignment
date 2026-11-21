package ee.ut.proofofprompt.proof_of_prompt.crypto;

import java.util.Base64;
import java.util.Objects;

public record SignedRequestAcceptanceReceipt(
        RequestAcceptanceReceiptBody receiptBody,
        String receiptSignatureBase64) {

    public SignedRequestAcceptanceReceipt {
        Objects.requireNonNull(receiptBody, "receiptBody");
        Objects.requireNonNull(receiptSignatureBase64, "receiptSignatureBase64");
        byte[] signatureBytes = Base64.getDecoder().decode(receiptSignatureBase64);
        if (signatureBytes.length != 64 ||
                !Base64.getEncoder().encodeToString(signatureBytes).equals(receiptSignatureBase64)) {
            throw new IllegalArgumentException("Expected a 64-byte Ed25519 signature in padded Base64");
        }
    }
}
