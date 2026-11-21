package ee.ut.proofofprompt.proof_of_prompt.crypto;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record RequestAcceptanceReceiptBody(
        int receiptProtocolVersion,
        UUID receiptId,
        UUID acceptedRequestId,
        String selectedModelId,
        String requestMessagesMerkleRoot,
        int messageCountInRequest,
        RequestAcceptanceStatus requestAcceptanceStatus,
        Instant requestAcceptedAtUtc,
        String receiptSigningKeyId) {

    public RequestAcceptanceReceiptBody {
        if (receiptProtocolVersion != 1) {
            throw new IllegalArgumentException("Unsupported receipt protocol version");
        }
        Objects.requireNonNull(receiptId, "receiptId");
        Objects.requireNonNull(acceptedRequestId, "acceptedRequestId");
        requireNonBlankIdentifier(selectedModelId, "selectedModelId");
        Hashing.requireHash(requestMessagesMerkleRoot);
        if (messageCountInRequest < 1) {
            throw new IllegalArgumentException("An accepted request must contain at least one message");
        }
        Objects.requireNonNull(requestAcceptanceStatus, "requestAcceptanceStatus");
        Objects.requireNonNull(requestAcceptedAtUtc, "requestAcceptedAtUtc");
        requireNonBlankIdentifier(receiptSigningKeyId, "receiptSigningKeyId");
    }

    public boolean recordsAcceptanceOf(UUID expectedRequestId) {
        return acceptedRequestId.equals(expectedRequestId) && requestAcceptanceStatus == RequestAcceptanceStatus.ACCEPTED;
    }

    static void requireNonBlankIdentifier(String identifier, String fieldName) {
        if (identifier == null || identifier.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must contain a non-blank identifier");
        }
        CanonicalMessage.validateUnicode(identifier);
    }
}
