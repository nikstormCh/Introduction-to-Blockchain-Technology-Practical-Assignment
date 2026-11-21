package ee.ut.proofofprompt.proof_of_prompt.workflow;

import ee.ut.proofofprompt.proof_of_prompt.crypto.CanonicalReceiptEncoder;
import ee.ut.proofofprompt.proof_of_prompt.crypto.CommittedRequest;
import ee.ut.proofofprompt.proof_of_prompt.crypto.MessageRole;
import ee.ut.proofofprompt.proof_of_prompt.crypto.RequestAcceptanceReceiptIssuer;
import ee.ut.proofofprompt.proof_of_prompt.persistence.AcceptedRequestEvidenceRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

import java.security.KeyPairGenerator;
import java.time.Clock;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

class RequestSubmissionControllerTest {
    @Test
    void operatorSubmitsOnlyTextAndReceivesTheSignedReceiptOnFailure() throws Exception {
        var signingKey = KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPrivate();
        var issuer = new RequestAcceptanceReceiptIssuer(signingKey, "test-key", Clock.systemUTC());
        var evidenceRepository = mock(AcceptedRequestEvidenceRepository.class);
        var groqClient = mock(GroqChatClient.class);
        var service = new RequestProcessingService(issuer, evidenceRepository, groqClient,
                mock(ModelExecutionRepository.class), "openai/gpt-oss-20b");
        var mapper = JsonMapper.builder().build();
        var mvc = MockMvcBuilders.standaloneSetup(new RequestSubmissionController(service, mapper, "System rule", "")).build();

        var invalid = mvc.perform(post("/api/requests").contentType("application/json")
                .content("{\"text\":\"Hello\",\"role\":\"system\"}")).andReturn().getResponse();
        assertEquals(400, invalid.getStatus());
        verifyNoInteractions(evidenceRepository, groqClient);

        when(groqClient.callModel(anyString(), any())).thenReturn(ModelExecutionOutcome.failed("Groq returned HTTP 429"));
        var response = mvc.perform(post("/api/requests").contentType("application/json")
                .content("{\"text\":\"Hello\"}")).andReturn().getResponse();
        assertEquals(201, response.getStatus());
        var result = mapper.readTree(response.getContentAsByteArray());
        var snapshot = ArgumentCaptor.forClass(CommittedRequest.class);
        verify(groqClient).callModel(eq("openai/gpt-oss-20b"), snapshot.capture());
        var messages = snapshot.getValue().messages();
        assertEquals(2, messages.size());
        assertEquals(MessageRole.SYSTEM, messages.getFirst().role());
        assertEquals("System rule", messages.getFirst().content());
        assertEquals(MessageRole.USER, messages.getLast().role());
        assertEquals("Hello", messages.getLast().content());
        var saved = ArgumentCaptor.forClass(ee.ut.proofofprompt.proof_of_prompt.persistence.AcceptedRequestEvidence.class);
        verify(evidenceRepository).saveAcceptedRequestEvidence(saved.capture());
        assertEquals(mapper.readTree(CanonicalReceiptEncoder.encodeSignedReceipt(saved.getValue().signedReceipt())), result.path("signedReceipt"));
        assertEquals("FAILED", result.path("modelExecution").path("executionStatus").asString());
        assertTrue(result.path("modelOutcomeSaved").asBoolean());

        doThrow(new DataAccessResourceFailureException("offline")).when(evidenceRepository).saveAcceptedRequestEvidence(any());
        var unavailable = mvc.perform(post("/api/requests").contentType("application/json")
                .content("{\"text\":\"Second request\"}")).andReturn().getResponse();
        assertEquals(503, unavailable.getStatus());
        verifyNoMoreInteractions(groqClient);
    }

    @Test
    void declaresUtf8AndPreservesRussianModelResponse() throws Exception {
        var signingKey = KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPrivate();
        var issuer = new RequestAcceptanceReceiptIssuer(signingKey, "test-key", Clock.systemUTC());
        var groqClient = mock(GroqChatClient.class);
        String modelResponse = "Яблоко — сочный фрукт.";
        when(groqClient.callModel(anyString(), any())).thenReturn(ModelExecutionOutcome.succeeded(modelResponse));
        var service = new RequestProcessingService(issuer, mock(AcceptedRequestEvidenceRepository.class),
                groqClient, mock(ModelExecutionRepository.class), "test-model");
        var mapper = JsonMapper.builder().build();
        var mvc = MockMvcBuilders.standaloneSetup(new RequestSubmissionController(service, mapper, "System rule", "")).build();

        var response = mvc.perform(post("/api/requests").contentType("application/json")
                .content("{\"text\":\"Hello\"}")).andReturn().getResponse();

        assertEquals(201, response.getStatus());
        var contentType = org.springframework.http.MediaType.parseMediaType(response.getContentType());
        assertEquals(java.nio.charset.StandardCharsets.UTF_8, contentType.getCharset());
        String decodedResponse = new String(response.getContentAsByteArray(), contentType.getCharset());
        assertEquals(modelResponse, mapper.readTree(decodedResponse).path("modelExecution").path("modelResponse").asString());
    }
}
