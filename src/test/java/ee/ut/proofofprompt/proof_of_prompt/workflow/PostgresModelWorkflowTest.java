package ee.ut.proofofprompt.proof_of_prompt.workflow;

import ee.ut.proofofprompt.proof_of_prompt.crypto.CanonicalReceiptEncoder;
import ee.ut.proofofprompt.proof_of_prompt.crypto.MerkleTree;
import ee.ut.proofofprompt.proof_of_prompt.crypto.ReceiptSignatureVerifier;
import ee.ut.proofofprompt.proof_of_prompt.crypto.RequestAcceptanceReceiptIssuer;
import ee.ut.proofofprompt.proof_of_prompt.crypto.SignedReceiptHasher;
import ee.ut.proofofprompt.proof_of_prompt.persistence.AcceptedRequestEvidence;
import ee.ut.proofofprompt.proof_of_prompt.persistence.AcceptedRequestEvidenceRepository;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@SpringBootTest
@EnabledIfEnvironmentVariable(named = "PROOF_RESTART_PHASE", matches = "write")
class PostgresModelWorkflowTest {
    @Autowired private AcceptedRequestEvidenceRepository evidenceRepository;
    @Autowired private ModelExecutionRepository executionRepository;
    @Autowired private JdbcTemplate database;
    @Autowired private JsonMapper mapper;

    @ParameterizedTest
    @ValueSource(ints = {200, 429})
    void checksCommittedEvidenceAtTheProviderAndAfterTheCall(int providerStatus) throws Exception {
        String userText = "Test " + UUID.randomUUID();
        KeyPair signer = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var observedPayload = new AtomicReference<JsonNode>();
        var evidenceAtCallTime = new AtomicReference<AcceptedRequestEvidence>();
        var httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        httpServer.createContext("/chat", exchange -> {
            try (exchange) {
                observedPayload.set(mapper.readTree(exchange.getRequestBody()));
                UUID requestId = database.queryForObject("""
                        SELECT m.accepted_request_id FROM proof_of_prompt.request_messages m
                        JOIN proof_of_prompt.model_executions e USING (accepted_request_id)
                        WHERE m.message_content_utf8 = ? AND e.execution_status = 'NOT_STARTED'
                        """, UUID.class, userText.getBytes(StandardCharsets.UTF_8));
                evidenceAtCallTime.set(evidenceRepository.findAcceptedRequestEvidence(requestId).orElseThrow());
                byte[] body = """
                        {"choices":[{"message":{"role":"assistant","content":"Test response"}}]}
                        """.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(providerStatus, body.length);
                exchange.getResponseBody().write(body);
            }
        });
        httpServer.start();
        try {
            var client = new GroqChatClient(HttpClient.newHttpClient(),
                    URI.create("http://127.0.0.1:" + httpServer.getAddress().getPort() + "/chat"), "test", mapper);
            var issuer = new RequestAcceptanceReceiptIssuer(signer.getPrivate(), "test-key", Clock.systemUTC());
            var service = new RequestProcessingService(issuer, evidenceRepository, client, executionRepository, "openai/gpt-oss-20b");
            var controller = new RequestSubmissionController(service, mapper, "System rule", "Developer rule");
            var mvc = MockMvcBuilders.standaloneSetup(controller).build();
            var response = mvc.perform(post("/api/requests").contentType("application/json")
                    .content(mapper.writeValueAsBytes(mapper.createObjectNode().put("text", userText))))
                    .andReturn().getResponse();
            assertEquals(201, response.getStatus());
            var result = mapper.readTree(response.getContentAsByteArray());
            var requestId = UUID.fromString(result.path("acceptedRequestId").asString());

            var original = evidenceAtCallTime.get();
            assertNotNull(original);
            assertEquals(original.committedRequest().acceptedRequestId(), requestId);
            var expectedPayload = mapper.createObjectNode().put("model", "openai/gpt-oss-20b")
                    .put("reasoning_effort", "low").put("max_completion_tokens", 1024).put("stream", false);
            var expectedMessages = expectedPayload.putArray("messages");
            expectedMessages.addObject().put("role", "system").put("content", "System rule");
            expectedMessages.addObject().put("role", "developer").put("content", "Developer rule");
            expectedMessages.addObject().put("role", "user").put("content", userText);
            assertEquals(expectedPayload, observedPayload.get());

            var saved = evidenceRepository.findAcceptedRequestEvidence(requestId).orElseThrow();
            var receipt = saved.signedReceipt();
            assertArrayEquals(CanonicalReceiptEncoder.encodeSignedReceipt(original.signedReceipt()),
                    CanonicalReceiptEncoder.encodeSignedReceipt(receipt));
            assertEquals(SignedReceiptHasher.computeSignedReceiptHash(original.signedReceipt()), SignedReceiptHasher.computeSignedReceiptHash(receipt));
            assertEquals(mapper.readTree(CanonicalReceiptEncoder.encodeSignedReceipt(receipt)), result.path("signedReceipt"));
            assertTrue(ReceiptSignatureVerifier.verifyReceiptSignature(receipt, signer.getPublic()));
            var disclosure = saved.committedRequest().disclose(2);
            assertTrue(MerkleTree.verify(disclosure.message(), disclosure.proof(), receipt.receiptBody().requestMessagesMerkleRoot(),
                    receipt.receiptBody().messageCountInRequest()));

            String expectedStatus = providerStatus == 200 ? "SUCCEEDED" : "FAILED";
            assertEquals(expectedStatus, result.path("modelExecution").path("executionStatus").asString());
            assertTrue(result.path("modelOutcomeSaved").asBoolean());
            var storedOutcome = database.queryForMap("""
                    SELECT execution_status, convert_from(model_response_utf8, 'UTF8') AS answer, failure_description
                    FROM proof_of_prompt.model_executions WHERE accepted_request_id = ?
                    """, requestId);
            assertEquals(expectedStatus, storedOutcome.get("execution_status"));
            assertEquals(providerStatus == 200 ? "Test response" : null, storedOutcome.get("answer"));
            assertEquals(providerStatus == 429 ? "Groq returned HTTP 429" : null, storedOutcome.get("failure_description"));
        } finally {
            httpServer.stop(0);
        }
    }

    @Test
    void rollbackDuringMessageInsertPreventsTheExternalCall() throws Exception {
        var client = org.mockito.Mockito.mock(GroqChatClient.class);
        var signer = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var issuer = new RequestAcceptanceReceiptIssuer(signer.getPrivate(), "test-key", Clock.systemUTC());
        var service = new RequestProcessingService(issuer, evidenceRepository, client, executionRepository, "test-model");
        var controller = new RequestSubmissionController(service, mapper, "System rule", "");
        assertThrows(DataIntegrityViolationException.class, () -> controller.submitRequest(
                mapper.createObjectNode().put("text", "Trigger the test-only constraint")));
        org.mockito.Mockito.verifyNoInteractions(client);
    }
}
