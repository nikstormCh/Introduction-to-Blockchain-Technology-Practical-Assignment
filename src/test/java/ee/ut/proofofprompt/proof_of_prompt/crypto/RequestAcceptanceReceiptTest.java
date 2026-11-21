package ee.ut.proofofprompt.proof_of_prompt.crypto;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RequestAcceptanceReceiptTest {
    // Generated independently with Node.js crypto and sorted JSON keys.
    private static final String REFERENCE_PUBLIC_KEY = "MCowBQYDK2VwAyEAVxZO7e7eGUvdJDkFPW9WzY1rJMpSNBopb0Kmg3uoyGY=";
    private static final String REFERENCE_SIGNATURE = "zqHqn24OMi+jGDzbjYBO86+6yjaIQ4Jjs2zq1LEBVqa4Ug9WJFef/MhT4kYo2zli4NearvEYSAmwNTjjYYydAg==";
    private static final String REFERENCE_RECEIPT_HASH = "566630fcf1f51451105fa8ced00977273b09dcef2fad24c48f1a5e97bede3f2c";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static byte[] readFixture(String fileName) throws IOException {
        try (InputStream fixture = RequestAcceptanceReceiptTest.class.getResourceAsStream("/" + fileName)) {
            assertNotNull(fixture, fileName);
            return fixture.readAllBytes();
        }
    }

    private static PublicKey referencePublicKey() throws Exception {
        byte[] publicKeyBytes = Base64.getDecoder().decode(REFERENCE_PUBLIC_KEY);
        return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(publicKeyBytes));
    }

    private static RequestAcceptanceReceiptBody referenceReceiptBody() throws IOException {
        return JSON.readValue(readFixture("step2-receipt-body.json"), RequestAcceptanceReceiptBody.class);
    }

    @Test
    void matchesIndependentCanonicalBytesSignatureAndHash() throws Exception {
        RequestAcceptanceReceiptBody receiptBody = referenceReceiptBody();
        SignedRequestAcceptanceReceipt signedReceipt = new SignedRequestAcceptanceReceipt(receiptBody, REFERENCE_SIGNATURE);
        assertArrayEquals(readFixture("step2-receipt-body.json"), CanonicalReceiptEncoder.encodeReceiptBody(receiptBody));
        assertArrayEquals(readFixture("step2-signed-receipt.json"), CanonicalReceiptEncoder.encodeSignedReceipt(signedReceipt));
        assertTrue(ReceiptSignatureVerifier.verifyReceiptSignature(signedReceipt, referencePublicKey()));
        assertEquals(REFERENCE_RECEIPT_HASH, SignedReceiptHasher.computeSignedReceiptHash(signedReceipt));
    }

    @ParameterizedTest(name = "Changing {0} invalidates the signature")
    @CsvSource({
            "acceptedRequestId, 00000000-0000-4000-8000-000000000009",
            "receiptId, 00000000-0000-4000-8000-000000000008",
            "selectedModelId, another-model",
            "requestMessagesMerkleRoot, cdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcd",
            "messageCountInRequest, 4",
            "requestAcceptedAtUtc, 2026-11-18T14:30:11.123Z",
            "receiptSigningKeyId, another-key"
    })
    void rejectsSingleFieldSubstitution(String fieldName, String replacementValue) throws Exception {
        ObjectNode alteredFields = (ObjectNode) JSON.readTree(readFixture("step2-receipt-body.json"));
        if (fieldName.equals("messageCountInRequest")) {
            alteredFields.put(fieldName, Integer.parseInt(replacementValue));
        } else {
            alteredFields.put(fieldName, replacementValue);
        }
        RequestAcceptanceReceiptBody alteredBody = JSON.treeToValue(alteredFields, RequestAcceptanceReceiptBody.class);
        SignedRequestAcceptanceReceipt alteredReceipt = new SignedRequestAcceptanceReceipt(alteredBody, REFERENCE_SIGNATURE);
        assertFalse(ReceiptSignatureVerifier.verifyReceiptSignature(alteredReceipt, referencePublicKey()));
    }

    @Test
    void rejectsUnsupportedVersionAndAcceptanceStatus() throws Exception {
        ObjectNode alteredFields = (ObjectNode) JSON.readTree(readFixture("step2-receipt-body.json"));
        alteredFields.put("receiptProtocolVersion", 2);
        assertThrows(tools.jackson.core.JacksonException.class,
                () -> JSON.treeToValue(alteredFields, RequestAcceptanceReceiptBody.class));
        alteredFields.put("receiptProtocolVersion", 1);
        alteredFields.put("requestAcceptanceStatus", "FAILED");
        assertThrows(tools.jackson.core.JacksonException.class,
                () -> JSON.treeToValue(alteredFields, RequestAcceptanceReceiptBody.class));
    }

    @Test
    void rejectsCorruptSignatureAndUntrustedKey() throws Exception {
        RequestAcceptanceReceiptBody receiptBody = referenceReceiptBody();
        SignedRequestAcceptanceReceipt originalReceipt = new SignedRequestAcceptanceReceipt(receiptBody, REFERENCE_SIGNATURE);
        KeyPair otherKeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        assertFalse(ReceiptSignatureVerifier.verifyReceiptSignature(originalReceipt, otherKeyPair.getPublic()));

        byte[] changedSignature = Base64.getDecoder().decode(REFERENCE_SIGNATURE);
        changedSignature[0] ^= 1;
        SignedRequestAcceptanceReceipt changedReceipt = new SignedRequestAcceptanceReceipt(receiptBody,
                Base64.getEncoder().encodeToString(changedSignature));
        assertFalse(ReceiptSignatureVerifier.verifyReceiptSignature(changedReceipt, referencePublicKey()));
        assertNotEquals(REFERENCE_RECEIPT_HASH, SignedReceiptHasher.computeSignedReceiptHash(changedReceipt));

        Arrays.fill(changedSignature, (byte) 0xff);
        SignedRequestAcceptanceReceipt malformedReceipt = new SignedRequestAcceptanceReceipt(receiptBody,
                Base64.getEncoder().encodeToString(changedSignature));
        assertFalse(ReceiptSignatureVerifier.verifyReceiptSignature(malformedReceipt, referencePublicKey()));
        assertThrows(IllegalArgumentException.class, () -> new SignedRequestAcceptanceReceipt(receiptBody, "!"));
        assertThrows(IllegalArgumentException.class, () -> new SignedRequestAcceptanceReceipt(receiptBody, "AA=="));
        assertThrows(IllegalArgumentException.class, () -> new SignedRequestAcceptanceReceipt(receiptBody,
                REFERENCE_SIGNATURE.replace("=", "")));
    }

    @Test
    void verifiesExpectedRequestAndSelectiveDisclosureAfterCheckingSignature() throws Exception {
        List<InputMessage> inputMessages = List.of(
                new InputMessage(MessageRole.SYSTEM, "Answer clearly."),
                new InputMessage(MessageRole.DEVELOPER, "Use metric units."),
                new InputMessage(MessageRole.USER, "How far is it?"));
        CommitmentService commitmentService = new CommitmentService();
        CommittedRequest expectedRequest = commitmentService.commit(inputMessages);
        CommittedRequest otherRequest = commitmentService.commit(inputMessages);
        assertNotEquals(expectedRequest.acceptedRequestId(), otherRequest.acceptedRequestId());

        KeyPair serviceKeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        Instant acceptanceTime = Instant.parse("2026-11-18T12:34:56Z");
        RequestAcceptanceReceiptIssuer receiptIssuer = new RequestAcceptanceReceiptIssuer(serviceKeyPair.getPrivate(),
                "service-key", Clock.fixed(acceptanceTime, ZoneOffset.UTC));
        SignedRequestAcceptanceReceipt expectedReceipt = receiptIssuer.issueSignedReceipt(expectedRequest, "test-model");
        SignedRequestAcceptanceReceipt otherReceipt = receiptIssuer.issueSignedReceipt(otherRequest, "test-model");
        assertTrue(ReceiptSignatureVerifier.verifyReceiptSignature(expectedReceipt, serviceKeyPair.getPublic()));
        assertTrue(ReceiptSignatureVerifier.verifyReceiptSignature(otherReceipt, serviceKeyPair.getPublic()));
        assertFalse(otherReceipt.receiptBody().recordsAcceptanceOf(expectedRequest.acceptedRequestId()));

        RequestAcceptanceReceiptBody verifiedBody = expectedReceipt.receiptBody();
        assertTrue(verifiedBody.recordsAcceptanceOf(expectedRequest.acceptedRequestId()));
        assertNotEquals(verifiedBody.receiptId(), otherReceipt.receiptBody().receiptId());
        assertEquals(acceptanceTime, verifiedBody.requestAcceptedAtUtc());
        assertEquals("test-model", verifiedBody.selectedModelId());
        assertEquals("service-key", verifiedBody.receiptSigningKeyId());
        assertEquals(expectedRequest.merkleRoot(), verifiedBody.requestMessagesMerkleRoot());
        assertEquals(inputMessages.size(), verifiedBody.messageCountInRequest());

        Disclosure disclosure = expectedRequest.disclose(1);
        PromptMessage originalMessage = disclosure.message();
        assertTrue(MerkleTree.verify(originalMessage, disclosure.proof(),
                verifiedBody.requestMessagesMerkleRoot(), verifiedBody.messageCountInRequest()));
        PromptMessage alteredMessage = new PromptMessage(originalMessage.protocolVersion(), originalMessage.position(),
                originalMessage.role(), "Use imperial units.", originalMessage.randomValue());
        assertFalse(MerkleTree.verify(alteredMessage, disclosure.proof(),
                verifiedBody.requestMessagesMerkleRoot(), verifiedBody.messageCountInRequest()));
    }
}
