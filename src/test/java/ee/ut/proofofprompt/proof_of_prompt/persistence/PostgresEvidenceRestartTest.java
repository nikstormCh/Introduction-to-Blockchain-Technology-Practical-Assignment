package ee.ut.proofofprompt.proof_of_prompt.persistence;

import ee.ut.proofofprompt.proof_of_prompt.crypto.CanonicalReceiptEncoder;
import ee.ut.proofofprompt.proof_of_prompt.crypto.CommitmentService;
import ee.ut.proofofprompt.proof_of_prompt.crypto.InputMessage;
import ee.ut.proofofprompt.proof_of_prompt.crypto.MerkleTree;
import ee.ut.proofofprompt.proof_of_prompt.crypto.MessageRole;
import ee.ut.proofofprompt.proof_of_prompt.crypto.ReceiptSignatureVerifier;
import ee.ut.proofofprompt.proof_of_prompt.crypto.RequestAcceptanceReceiptIssuer;
import ee.ut.proofofprompt.proof_of_prompt.crypto.SignedReceiptHasher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@EnabledIfEnvironmentVariable(named = "PROOF_RESTART_CHECK_DIR", matches = ".+")
class PostgresEvidenceRestartTest {
    private static final String DEVELOPER_MESSAGE = "Check 🚀\r\n" + (char) 0 + " before answering.";
    private final Path checkpointDirectory = Path.of(System.getenv("PROOF_RESTART_CHECK_DIR"));

    @Autowired
    private AcceptedRequestEvidenceRepository evidenceRepository;

    @Test
    @EnabledIfEnvironmentVariable(named = "PROOF_RESTART_PHASE", matches = "write")
    void saveOriginalEvidenceAndCheckTransactionBoundaries() throws Exception {
        KeyPair serviceKeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var receiptIssuer = new RequestAcceptanceReceiptIssuer(serviceKeyPair.getPrivate(), "test-key",
                Clock.fixed(Instant.parse("2026-11-18T12:30:00.123456789Z"), ZoneOffset.UTC));
        var commitmentService = new CommitmentService();
        var request = commitmentService.commit(List.of(
                new InputMessage(MessageRole.SYSTEM, ""),
                new InputMessage(MessageRole.DEVELOPER, DEVELOPER_MESSAGE),
                new InputMessage(MessageRole.USER, "Question?")));
        var receipt = receiptIssuer.issueSignedReceipt(request, "test-model");
        var evidence = new AcceptedRequestEvidence(request, receipt);
        assertTrue(evidenceRepository.findAcceptedRequestEvidence(request.acceptedRequestId()).isEmpty());
        evidenceRepository.saveAcceptedRequestEvidence(evidence);
        assertThrows(DuplicateKeyException.class, () -> evidenceRepository.saveAcceptedRequestEvidence(evidence));

        var requestForRollback = commitmentService.commit(List.of(
                new InputMessage(MessageRole.USER, "First insert in this batch"),
                new InputMessage(MessageRole.USER, "Trigger the test-only constraint")));
        assertThrows(IllegalArgumentException.class, () -> new AcceptedRequestEvidence(requestForRollback, receipt));
        var evidenceForRollback = new AcceptedRequestEvidence(requestForRollback,
                receiptIssuer.issueSignedReceipt(requestForRollback, "test-model"));
        assertThrows(DataIntegrityViolationException.class,
                () -> evidenceRepository.saveAcceptedRequestEvidence(evidenceForRollback));
        assertTrue(evidenceRepository.findAcceptedRequestEvidence(requestForRollback.acceptedRequestId()).isEmpty());
        assertEquals(receipt, evidenceRepository.findAcceptedRequestEvidence(request.acceptedRequestId())
                .orElseThrow().signedReceipt());

        // Only public expectations cross the restart outside the database.
        Files.writeString(checkpointDirectory.resolve("request-id"), request.acceptedRequestId().toString());
        Files.write(checkpointDirectory.resolve("public-key"), serviceKeyPair.getPublic().getEncoded());
        Files.write(checkpointDirectory.resolve("receipt"), CanonicalReceiptEncoder.encodeSignedReceipt(receipt));
        Files.writeString(checkpointDirectory.resolve("receipt-hash"), SignedReceiptHasher.computeSignedReceiptHash(receipt));
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "PROOF_RESTART_PHASE", matches = "read")
    void restoreTheOriginalSignedReceiptAndDiscloseOneMessage() throws Exception {
        UUID expectedRequestId = UUID.fromString(Files.readString(checkpointDirectory.resolve("request-id")));
        PublicKey trustedPublicKey = KeyFactory.getInstance("Ed25519").generatePublic(
                new X509EncodedKeySpec(Files.readAllBytes(checkpointDirectory.resolve("public-key"))));
        var restoredEvidence = evidenceRepository.findAcceptedRequestEvidence(expectedRequestId).orElseThrow();
        var restoredReceipt = restoredEvidence.signedReceipt();
        assertTrue(ReceiptSignatureVerifier.verifyReceiptSignature(restoredReceipt, trustedPublicKey));
        assertTrue(restoredReceipt.receiptBody().recordsAcceptanceOf(expectedRequestId));
        assertArrayEquals(Files.readAllBytes(checkpointDirectory.resolve("receipt")),
                CanonicalReceiptEncoder.encodeSignedReceipt(restoredReceipt));
        assertEquals(Files.readString(checkpointDirectory.resolve("receipt-hash")),
                SignedReceiptHasher.computeSignedReceiptHash(restoredReceipt));

        var restoredRequest = restoredEvidence.committedRequest();
        assertEquals(expectedRequestId, restoredRequest.acceptedRequestId());
        var disclosure = restoredRequest.disclose(1);
        assertEquals(DEVELOPER_MESSAGE, disclosure.message().content());
        assertEquals(MessageRole.DEVELOPER, disclosure.message().role());
        assertEquals(1, disclosure.message().position());
        assertTrue(MerkleTree.verify(disclosure.message(), disclosure.proof(),
                restoredReceipt.receiptBody().requestMessagesMerkleRoot(), restoredReceipt.receiptBody().messageCountInRequest()));
        Files.createFile(checkpointDirectory.resolve("verified-after-restart"));
    }
}
