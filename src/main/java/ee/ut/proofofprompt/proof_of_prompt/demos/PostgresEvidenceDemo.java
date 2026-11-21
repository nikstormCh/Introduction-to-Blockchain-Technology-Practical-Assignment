package ee.ut.proofofprompt.proof_of_prompt.demos;

import ee.ut.proofofprompt.proof_of_prompt.ProofOfPromptApplication;
import ee.ut.proofofprompt.proof_of_prompt.crypto.CommitmentService;
import ee.ut.proofofprompt.proof_of_prompt.crypto.InputMessage;
import ee.ut.proofofprompt.proof_of_prompt.crypto.MessageRole;
import ee.ut.proofofprompt.proof_of_prompt.crypto.RequestAcceptanceReceiptIssuer;
import ee.ut.proofofprompt.proof_of_prompt.crypto.SignedReceiptHasher;
import ee.ut.proofofprompt.proof_of_prompt.crypto.SignedRequestAcceptanceReceipt;
import ee.ut.proofofprompt.proof_of_prompt.persistence.AcceptedRequestEvidence;
import ee.ut.proofofprompt.proof_of_prompt.persistence.AcceptedRequestEvidenceRepository;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

import java.security.GeneralSecurityException;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.util.List;
import java.util.UUID;

public final class PostgresEvidenceDemo {
    private PostgresEvidenceDemo() {}

    public static void main(String[] args) throws GeneralSecurityException {
        boolean saveRequested = args.length == 1 && "save".equals(args[0]);
        boolean readRequested = args.length == 2 && "read".equals(args[0]);
        if (!saveRequested && !readRequested) {
            throw new IllegalArgumentException("Use save or read <accepted-request-uuid>");
        }

        try (var application = new SpringApplicationBuilder(ProofOfPromptApplication.class)
                .web(WebApplicationType.NONE).run()) {
            var evidenceRepository = application.getBean(AcceptedRequestEvidenceRepository.class);
            if (saveRequested) {
                saveExampleRequest(evidenceRepository);
            } else {
                readSavedRequest(evidenceRepository, UUID.fromString(args[1]));
            }
        }
    }

    private static void saveExampleRequest(AcceptedRequestEvidenceRepository evidenceRepository)
            throws GeneralSecurityException {
        var orderedMessages = List.of(
                new InputMessage(MessageRole.SYSTEM, "Answer in one sentence."),
                new InputMessage(MessageRole.USER, "What is an apple?"));
        var committedRequest = new CommitmentService().commit(orderedMessages);

        // This demo signs one example and discards its temporary key when the process exits.
        var signingKeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var receiptIssuer = new RequestAcceptanceReceiptIssuer(signingKeyPair.getPrivate(),
                "postgres-demo-" + committedRequest.acceptedRequestId(), Clock.systemUTC());
        var signedReceipt = receiptIssuer.issueSignedReceipt(committedRequest, "demo-model");

        var evidenceToSave = new AcceptedRequestEvidence(committedRequest, signedReceipt);
        evidenceRepository.saveAcceptedRequestEvidence(evidenceToSave);
        System.out.println("Saved evidence in PostgreSQL.");
        printReceiptIdentifiers(signedReceipt);
    }

    private static void readSavedRequest(AcceptedRequestEvidenceRepository evidenceRepository, UUID acceptedRequestId) {
        var storedEvidence = evidenceRepository.findAcceptedRequestEvidence(acceptedRequestId);
        if (storedEvidence.isEmpty()) {
            System.out.println("No evidence found for request " + acceptedRequestId);
            return;
        }

        var restoredEvidence = storedEvidence.orElseThrow();
        System.out.println("Read evidence from PostgreSQL.");
        printReceiptIdentifiers(restoredEvidence.signedReceipt());
        for (var message : restoredEvidence.committedRequest().messages()) {
            System.out.println("Restored message " + message.position() + " (" + message.role() + ")");
        }
    }

    private static void printReceiptIdentifiers(SignedRequestAcceptanceReceipt signedReceipt) {
        var receiptBody = signedReceipt.receiptBody();
        System.out.println("Accepted request ID: " + receiptBody.acceptedRequestId());
        System.out.println("Receipt ID: " + receiptBody.receiptId());
        System.out.println("Message count: " + receiptBody.messageCountInRequest());
        System.out.println("Merkle root: " + receiptBody.requestMessagesMerkleRoot());
        System.out.println("Signed receipt hash: " + SignedReceiptHasher.computeSignedReceiptHash(signedReceipt));
    }
}
