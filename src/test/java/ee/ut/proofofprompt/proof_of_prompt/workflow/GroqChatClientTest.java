package ee.ut.proofofprompt.proof_of_prompt.workflow;

import ee.ut.proofofprompt.proof_of_prompt.crypto.CommitmentService;
import ee.ut.proofofprompt.proof_of_prompt.crypto.CommittedRequest;
import ee.ut.proofofprompt.proof_of_prompt.crypto.InputMessage;
import ee.ut.proofofprompt.proof_of_prompt.crypto.MessageRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class GroqChatClientTest {
    private final HttpClient transport = mock(HttpClient.class);
    private final GroqChatClient groqClient = new GroqChatClient(transport, URI.create("https://example.invalid"),
            "test-key", JsonMapper.builder().build());
    private final CommittedRequest request = new CommitmentService().commit(
            List.of(new InputMessage(MessageRole.USER, "Question")));
    private final HttpResponse<String> response = mock();

    @BeforeEach
    void prepareResponse() throws Exception {
        when(transport.send(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
                .thenReturn(response);
        when(response.statusCode()).thenReturn(200);
    }

    @Test
    void readsOnlyAssistantContent() throws Exception {
        when(response.body()).thenReturn("""
                {"choices":[{"message":{"role":"assistant","content":"Final answer","reasoning":"private"}}]}
                """);
        assertEquals(ModelExecutionOutcome.succeeded("Final answer"), groqClient.callModel("test-model", request));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "null", "{", "{}", "{} {}", "{\"choices\":[]}", "{\"choices\":[{\"message\":{\"content\":123}}]}"})
    void malformedOrEmptyResponsesFail(String responseBody) throws Exception {
        when(response.body()).thenReturn(responseBody);
        assertEquals(ModelExecutionStatus.FAILED, groqClient.callModel("test-model", request).executionStatus());
    }

    @Test
    void reportsHttpFailureWithoutEchoingProviderData() throws Exception {
        when(response.statusCode()).thenReturn(429);
        when(response.body()).thenReturn("Sensitive details");
        assertEquals(ModelExecutionOutcome.failed("Groq returned HTTP 429"), groqClient.callModel("test-model", request));
    }

    @Test
    void handlesTimeoutsAndNetworkFailures() throws Exception {
        when(transport.send(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
                .thenThrow(new HttpTimeoutException("timeout")).thenThrow(new IOException("connection failed"));
        assertEquals(ModelExecutionOutcome.failed("Groq did not respond within the timeout"), groqClient.callModel("test-model", request));
        assertEquals(ModelExecutionOutcome.failed("Could not complete the connection to Groq"), groqClient.callModel("test-model", request));
    }
}
