package ee.ut.proofofprompt.proof_of_prompt.workflow;

import ee.ut.proofofprompt.proof_of_prompt.crypto.CommitmentService;
import ee.ut.proofofprompt.proof_of_prompt.crypto.InputMessage;
import ee.ut.proofofprompt.proof_of_prompt.crypto.RequestAcceptanceReceiptIssuer;
import ee.ut.proofofprompt.proof_of_prompt.persistence.AcceptedRequestEvidence;
import ee.ut.proofofprompt.proof_of_prompt.persistence.AcceptedRequestEvidenceRepository;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.security.GeneralSecurityException;
import java.util.List;

public class RequestProcessingService {
    private final RequestAcceptanceReceiptIssuer receiptIssuer;
    private final AcceptedRequestEvidenceRepository evidenceRepository;
    private final GroqChatClient groqClient;
    private final ModelExecutionRepository executionRepository;
    private final CommitmentService commitmentService = new CommitmentService();
    private final String selectedModelId;

    public RequestProcessingService(RequestAcceptanceReceiptIssuer receiptIssuer,
                                    AcceptedRequestEvidenceRepository evidenceRepository, GroqChatClient groqClient,
                                    ModelExecutionRepository executionRepository, String selectedModelId) {
        this.receiptIssuer = receiptIssuer;
        this.evidenceRepository = evidenceRepository;
        this.groqClient = groqClient;
        this.executionRepository = executionRepository;
        this.selectedModelId = selectedModelId;
    }

    // The acceptance transaction must commit before sending anything to the provider.
    @Transactional(propagation = Propagation.NEVER)
    public ProcessedRequest acceptAndCallModel(List<InputMessage> finalOrderedMessages) throws GeneralSecurityException {
        var committedRequest = commitmentService.commit(finalOrderedMessages);
        var signedReceipt = receiptIssuer.issueSignedReceipt(committedRequest, selectedModelId);
        evidenceRepository.saveAcceptedRequestEvidence(new AcceptedRequestEvidence(committedRequest, signedReceipt));

        boolean interrupted = false;
        try {
            ModelExecutionOutcome outcome;
            try {
                outcome = groqClient.callModel(signedReceipt.receiptBody().selectedModelId(), committedRequest);
            } catch (InterruptedException interruptedCall) {
                interrupted = true;
                outcome = ModelExecutionOutcome.failed("Model call was interrupted");
            }
            try {
                executionRepository.recordFinalOutcome(committedRequest.acceptedRequestId(), outcome);
                return new ProcessedRequest(signedReceipt, outcome, true);
            } catch (DataAccessException | TransactionException persistenceFailure) {
                // The receipt is already durable. The final database write could not be confirmed.
                return new ProcessedRequest(signedReceipt, outcome, false);
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
