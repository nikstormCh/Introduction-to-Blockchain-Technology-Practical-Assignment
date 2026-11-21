package ee.ut.proofofprompt.proof_of_prompt.crypto;

public final class SignedReceiptHasher {
    private SignedReceiptHasher() {}

    public static String computeSignedReceiptHash(SignedRequestAcceptanceReceipt signedReceipt) {
        return Hashing.digest((byte) 0x02, CanonicalReceiptEncoder.encodeSignedReceipt(signedReceipt));
    }
}
