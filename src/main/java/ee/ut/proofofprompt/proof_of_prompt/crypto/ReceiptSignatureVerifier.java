package ee.ut.proofofprompt.proof_of_prompt.crypto;

import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.Signature;
import java.security.SignatureException;
import java.util.Base64;

public final class ReceiptSignatureVerifier {
    private ReceiptSignatureVerifier() {}

    public static boolean verifyReceiptSignature(SignedRequestAcceptanceReceipt signedReceipt,
                                                 PublicKey trustedServicePublicKey)
            throws InvalidKeyException, NoSuchAlgorithmException {
        Signature receiptVerifier = Signature.getInstance("Ed25519");
        receiptVerifier.initVerify(trustedServicePublicKey);
        byte[] receiptBodyBytes = CanonicalReceiptEncoder.encodeReceiptBody(signedReceipt.receiptBody());
        try {
            receiptVerifier.update(receiptBodyBytes);
        } catch (SignatureException verifierError) {
            throw new IllegalStateException("Unable to process receipt bytes", verifierError);
        }

        byte[] signatureBytes = Base64.getDecoder().decode(signedReceipt.receiptSignatureBase64());
        try {
            return receiptVerifier.verify(signatureBytes);
        } catch (SignatureException malformedSignature) {
            // A provider may reject a malformed Ed25519 point by throwing instead of returning false.
            return false;
        }
    }
}
