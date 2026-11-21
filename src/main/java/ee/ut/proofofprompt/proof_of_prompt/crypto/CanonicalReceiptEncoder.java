package ee.ut.proofofprompt.proof_of_prompt.crypto;

import java.nio.charset.StandardCharsets;

import static ee.ut.proofofprompt.proof_of_prompt.crypto.CanonicalMessage.quote;

/** Fixed-schema canonical JSON. Both forms use the same fields in sorted order. */
public final class CanonicalReceiptEncoder {
    private CanonicalReceiptEncoder() {}

    public static byte[] encodeReceiptBody(RequestAcceptanceReceiptBody receiptBody) {
        return encodeReceiptFields(receiptBody, null);
    }

    public static byte[] encodeSignedReceipt(SignedRequestAcceptanceReceipt signedReceipt) {
        return encodeReceiptFields(signedReceipt.receiptBody(), signedReceipt.receiptSignatureBase64());
    }

    private static byte[] encodeReceiptFields(RequestAcceptanceReceiptBody receiptBody, String receiptSignatureBase64) {
        StringBuilder json = new StringBuilder();
        json.append("{\"acceptedRequestId\":").append(quote(receiptBody.acceptedRequestId().toString()));
        json.append(",\"messageCountInRequest\":").append(receiptBody.messageCountInRequest());
        json.append(",\"receiptId\":").append(quote(receiptBody.receiptId().toString()));
        json.append(",\"receiptProtocolVersion\":").append(receiptBody.receiptProtocolVersion());
        if (receiptSignatureBase64 != null) {
            json.append(",\"receiptSignatureBase64\":").append(quote(receiptSignatureBase64));
        }
        json.append(",\"receiptSigningKeyId\":").append(quote(receiptBody.receiptSigningKeyId()));
        json.append(",\"requestAcceptanceStatus\":").append(quote(receiptBody.requestAcceptanceStatus().name()));
        json.append(",\"requestAcceptedAtUtc\":").append(quote(receiptBody.requestAcceptedAtUtc().toString()));
        json.append(",\"requestMessagesMerkleRoot\":").append(quote(receiptBody.requestMessagesMerkleRoot()));
        json.append(",\"selectedModelId\":").append(quote(receiptBody.selectedModelId()));
        json.append('}');
        return json.toString().getBytes(StandardCharsets.UTF_8);
    }
}
