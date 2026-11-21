package ee.ut.proofofprompt.proof_of_prompt.demos;

import ee.ut.proofofprompt.proof_of_prompt.ProofOfPromptApplication;
import ee.ut.proofofprompt.proof_of_prompt.crypto.InputMessage;
import ee.ut.proofofprompt.proof_of_prompt.crypto.MessageRole;
import ee.ut.proofofprompt.proof_of_prompt.persistence.AcceptedRequestEvidenceRepository;
import ee.ut.proofofprompt.proof_of_prompt.workflow.ProcessedRequest;
import ee.ut.proofofprompt.proof_of_prompt.workflow.RequestProcessingService;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.List;

public final class GroqRequestDemo {
    private GroqRequestDemo() {}

    public static void main(String[] args) throws IOException, GeneralSecurityException {
        if (args.length != 1 || !("live".equals(args[0]) || "429".equals(args[0]))) {
            throw new IllegalArgumentException("Use live for Groq or 429 for the local rate-limit example");
        }
        boolean callRealGroq = "live".equals(args[0]);
        var applicationBuilder = new SpringApplicationBuilder(ProofOfPromptApplication.class)
                .web(WebApplicationType.NONE);
        if (callRealGroq) {
            applicationBuilder.profiles("model-request");
        }

        try (var application = applicationBuilder.run()) {
            var orderedMessages = List.of(
                    new InputMessage(MessageRole.SYSTEM, "Answer in one short sentence."),
                    new InputMessage(MessageRole.USER, "What is an apple?"));
            ProcessedRequest processedRequest;
            if (callRealGroq) {
                var requestService = application.getBean(RequestProcessingService.class);
                processedRequest = requestService.acceptAndCallModel(orderedMessages);
            } else {
                processedRequest = LocalRateLimitExample.processWithHttp429(application, orderedMessages);
            }

            var signedReceipt = processedRequest.signedReceipt();
            var receiptBody = signedReceipt.receiptBody();
            var modelExecution = processedRequest.modelExecution();
            System.out.println("Accepted request ID: " + receiptBody.acceptedRequestId());
            System.out.println("Acceptance status: " + receiptBody.requestAcceptanceStatus());
            System.out.println("Model execution status: " + modelExecution.executionStatus());
            if (modelExecution.failureDescription() != null) {
                System.out.println("Failure: " + modelExecution.failureDescription());
            } else {
                System.out.println("Model response: " + modelExecution.modelResponse());
            }
            System.out.println("Model outcome saved: " + processedRequest.modelOutcomeSaved());

            var evidenceRepository = application.getBean(AcceptedRequestEvidenceRepository.class);
            var savedEvidence = evidenceRepository.findAcceptedRequestEvidence(receiptBody.acceptedRequestId())
                    .orElseThrow(() -> new IllegalStateException("The accepted request is missing from PostgreSQL"));
            if (!signedReceipt.equals(savedEvidence.signedReceipt())) {
                throw new IllegalStateException("The stored receipt differs from the issued receipt");
            }
            System.out.println("Identical signed receipt read from PostgreSQL.");
        }
    }
}
