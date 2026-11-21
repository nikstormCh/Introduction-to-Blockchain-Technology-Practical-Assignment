package ee.ut.proofofprompt.proof_of_prompt.workflow;

import ee.ut.proofofprompt.proof_of_prompt.crypto.InputMessage;
import ee.ut.proofofprompt.proof_of_prompt.crypto.MessageRole;
import ee.ut.proofofprompt.proof_of_prompt.crypto.RequestAcceptanceReceiptIssuer;
import ee.ut.proofofprompt.proof_of_prompt.persistence.AcceptedRequestEvidence;
import ee.ut.proofofprompt.proof_of_prompt.persistence.AcceptedRequestEvidenceRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;

import java.security.KeyPairGenerator;
import java.time.Clock;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RequestProcessingServiceTest {
    private final AcceptedRequestEvidenceRepository evidenceRepository = mock();
    private final ModelExecutionRepository executionRepository = mock();
    private final GroqChatClient groqClient = mock();
    private final List<InputMessage> messages = List.of(new InputMessage(MessageRole.USER, "Question"));

    private RequestProcessingService createService() throws Exception {
        var keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var issuer = new RequestAcceptanceReceiptIssuer(keyPair.getPrivate(), "test-key", Clock.systemUTC());
        return new RequestProcessingService(issuer, evidenceRepository, groqClient, executionRepository, "test-model");
    }

    @Test
    void doesNotCallModelIfAcceptanceFails() throws Exception {
        doThrow(new DataAccessResourceFailureException("unavailable")).when(evidenceRepository).saveAcceptedRequestEvidence(any());
        assertThrows(DataAccessResourceFailureException.class, () -> createService().acceptAndCallModel(messages));
        verifyNoInteractions(groqClient, executionRepository);
    }

    @Test
    void returnsOriginalReceiptEvenWhenRecordingTheFinalOutcomeFails() throws Exception {
        var modelFailure = ModelExecutionOutcome.failed("Groq returned HTTP 429");
        when(groqClient.callModel(eq("test-model"), any())).thenReturn(modelFailure);
        doThrow(new DataAccessResourceFailureException("unavailable")).when(executionRepository).recordFinalOutcome(any(), any());
        var result = createService().acceptAndCallModel(messages);
        var stored = ArgumentCaptor.forClass(AcceptedRequestEvidence.class);
        var calls = inOrder(evidenceRepository, groqClient, executionRepository);
        calls.verify(evidenceRepository).saveAcceptedRequestEvidence(stored.capture());
        calls.verify(groqClient).callModel("test-model", stored.getValue().committedRequest());
        calls.verify(executionRepository).recordFinalOutcome(stored.getValue().committedRequest().acceptedRequestId(), modelFailure);
        assertEquals(stored.getValue().signedReceipt(), result.signedReceipt());
        assertEquals(modelFailure, result.modelExecution());
        assertFalse(result.modelOutcomeSaved());
    }
}
