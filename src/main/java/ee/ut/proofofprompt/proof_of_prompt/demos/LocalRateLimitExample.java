package ee.ut.proofofprompt.proof_of_prompt.demos;

import com.sun.net.httpserver.HttpServer;
import ee.ut.proofofprompt.proof_of_prompt.crypto.InputMessage;
import ee.ut.proofofprompt.proof_of_prompt.crypto.RequestAcceptanceReceiptIssuer;
import ee.ut.proofofprompt.proof_of_prompt.persistence.AcceptedRequestEvidenceRepository;
import ee.ut.proofofprompt.proof_of_prompt.workflow.GroqChatClient;
import ee.ut.proofofprompt.proof_of_prompt.workflow.ModelExecutionRepository;
import ee.ut.proofofprompt.proof_of_prompt.workflow.ProcessedRequest;
import ee.ut.proofofprompt.proof_of_prompt.workflow.RequestProcessingService;
import org.springframework.context.ApplicationContext;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.security.GeneralSecurityException;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.util.List;
import java.util.UUID;

final class LocalRateLimitExample {
    private LocalRateLimitExample() {}

    static ProcessedRequest processWithHttp429(ApplicationContext application, List<InputMessage> orderedMessages)
            throws IOException, GeneralSecurityException {
        var localServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        localServer.createContext("/chat", exchange -> {
            try (exchange) {
                System.out.println("Local provider received the chat request. Returning HTTP 429.");
                exchange.sendResponseHeaders(429, -1);
            }
        });
        localServer.start();
        try (var httpTransport = HttpClient.newHttpClient()) {
            var localEndpoint = URI.create("http://127.0.0.1:" + localServer.getAddress().getPort() + "/chat");
            var groqClient = new GroqChatClient(httpTransport, localEndpoint, "demo-only",
                    application.getBean(JsonMapper.class));
            var demoSigningKey = KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPrivate();
            var receiptIssuer = new RequestAcceptanceReceiptIssuer(demoSigningKey,
                    "rate-limit-demo-" + UUID.randomUUID(), Clock.systemUTC());

            // Spring supplies both transactional repositories, just as it does for a live request.
            var requestService = new RequestProcessingService(receiptIssuer,
                    application.getBean(AcceptedRequestEvidenceRepository.class), groqClient,
                    application.getBean(ModelExecutionRepository.class), "openai/gpt-oss-20b");
            return requestService.acceptAndCallModel(orderedMessages);
        } finally {
            localServer.stop(0);
        }
    }
}
