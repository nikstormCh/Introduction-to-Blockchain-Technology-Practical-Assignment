package ee.ut.proofofprompt.proof_of_prompt.demos;

import ee.ut.proofofprompt.proof_of_prompt.crypto.CommitmentService;
import ee.ut.proofofprompt.proof_of_prompt.crypto.CommittedRequest;
import ee.ut.proofofprompt.proof_of_prompt.crypto.Disclosure;
import ee.ut.proofofprompt.proof_of_prompt.crypto.InputMessage;
import ee.ut.proofofprompt.proof_of_prompt.crypto.MerkleTree;
import ee.ut.proofofprompt.proof_of_prompt.crypto.MessageRole;
import ee.ut.proofofprompt.proof_of_prompt.crypto.ReceiptSignatureVerifier;
import ee.ut.proofofprompt.proof_of_prompt.crypto.RequestAcceptanceReceiptBody;
import ee.ut.proofofprompt.proof_of_prompt.crypto.RequestAcceptanceReceiptIssuer;
import ee.ut.proofofprompt.proof_of_prompt.crypto.SignedReceiptHasher;
import ee.ut.proofofprompt.proof_of_prompt.crypto.SignedRequestAcceptanceReceipt;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.util.List;
import java.util.UUID;

public final class ReceiptDemo {
    private ReceiptDemo() {}

    public static void main(String[] args) throws Exception {
        // This temporary key pair is for the demo. Supply the investigator's trusted public key independently.
        KeyPair demoServiceKeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        RequestAcceptanceReceiptIssuer receiptIssuer = new RequestAcceptanceReceiptIssuer(
                demoServiceKeyPair.getPrivate(), "demo-service-key", Clock.systemUTC());
        CommittedRequest committedRequest = new CommitmentService().commit(List.of(
                new InputMessage(MessageRole.SYSTEM, "Answer the user's question."),
                new InputMessage(MessageRole.DEVELOPER, "Ask for the order number."),
                new InputMessage(MessageRole.USER, "Where is my order?")));
        UUID expectedRequestId = committedRequest.acceptedRequestId();
        SignedRequestAcceptanceReceipt signedReceipt = receiptIssuer.issueSignedReceipt(committedRequest, "demo-model");

        boolean signatureVerified = ReceiptSignatureVerifier.verifyReceiptSignature(
                signedReceipt, demoServiceKeyPair.getPublic());
        System.out.println("Receipt signature verified: " + signatureVerified);
        if (!signatureVerified) {
            throw new IllegalStateException("Receipt signature check failed");
        }

        RequestAcceptanceReceiptBody verifiedReceiptBody = signedReceipt.receiptBody();
        if (!verifiedReceiptBody.recordsAcceptanceOf(expectedRequestId)) {
            throw new IllegalStateException("Receipt does not confirm acceptance of the expected request");
        }
        Disclosure developerMessageDisclosure = committedRequest.disclose(1);
        boolean disclosedMessageVerified = MerkleTree.verify(
                developerMessageDisclosure.message(), developerMessageDisclosure.proof(),
                verifiedReceiptBody.requestMessagesMerkleRoot(), verifiedReceiptBody.messageCountInRequest());
        System.out.println("Accepted request ID: " + verifiedReceiptBody.acceptedRequestId());
        System.out.println("Disclosed developer message verified: " + disclosedMessageVerified);
        System.out.println("Signed receipt hash: " + SignedReceiptHasher.computeSignedReceiptHash(signedReceipt));
        System.out.println("The receipt confirms service acceptance. Model execution and compliance are separate.");
    }
}
