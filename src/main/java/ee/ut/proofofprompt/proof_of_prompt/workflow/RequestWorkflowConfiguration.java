package ee.ut.proofofprompt.proof_of_prompt.workflow;

import ee.ut.proofofprompt.proof_of_prompt.crypto.RequestAcceptanceReceiptIssuer;
import ee.ut.proofofprompt.proof_of_prompt.persistence.AcceptedRequestEvidenceRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import tools.jackson.databind.json.JsonMapper;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.interfaces.EdECPrivateKey;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;

@Configuration(proxyBeanMethods = false)
@Profile("model-request")
public class RequestWorkflowConfiguration {
    @Bean
    RequestAcceptanceReceiptIssuer receiptIssuer(@Value("${proof.signing.keystore}") String keyStorePath,
                                               @Value("${proof.signing.password}") char[] password) throws Exception {
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (InputStream input = Files.newInputStream(Path.of(keyStorePath))) {
            keyStore.load(input, password);
            if (!(keyStore.getKey("receipt-signer", password) instanceof EdECPrivateKey signingKey)
                    || !"Ed25519".equals(signingKey.getParams().getName())) {
                throw new IllegalArgumentException("The receipt-signer alias must contain an Ed25519 private key");
            }
            byte[] publicKey = keyStore.getCertificate("receipt-signer").getPublicKey().getEncoded();
            String signingKeyId = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(publicKey));
            return new RequestAcceptanceReceiptIssuer(signingKey, signingKeyId, Clock.systemUTC());
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    @Bean
    HttpClient groqHttpTransport() {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    @Bean
    RequestProcessingService requestProcessingService(RequestAcceptanceReceiptIssuer receiptIssuer,
            AcceptedRequestEvidenceRepository evidenceRepository, ModelExecutionRepository executionRepository,
            HttpClient groqHttpTransport, JsonMapper jsonMapper, @Value("${proof.groq.api-key}") String apiKey,
            @Value("${proof.groq.model}") String selectedModelId) {
        var groqClient = new GroqChatClient(groqHttpTransport, URI.create("https://api.groq.com/openai/v1/chat/completions"),
                apiKey, jsonMapper);
        return new RequestProcessingService(receiptIssuer, evidenceRepository, groqClient, executionRepository, selectedModelId);
    }
}
