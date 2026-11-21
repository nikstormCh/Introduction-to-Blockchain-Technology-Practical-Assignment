package ee.ut.proofofprompt.proof_of_prompt.workflow;

import ee.ut.proofofprompt.proof_of_prompt.crypto.CanonicalReceiptEncoder;
import ee.ut.proofofprompt.proof_of_prompt.crypto.InputMessage;
import ee.ut.proofofprompt.proof_of_prompt.crypto.MessageRole;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.List;

@RestController
@Profile("model-request")
public class RequestSubmissionController {
    private final RequestProcessingService requestService;
    private final JsonMapper jsonMapper;
    private final String systemInstruction;
    private final String developerInstruction;

    public RequestSubmissionController(RequestProcessingService requestService, JsonMapper jsonMapper,
            @Value("${proof.prompt.system}") String systemInstruction,
            @Value("${proof.prompt.developer}") String developerInstruction) {
        this.requestService = requestService;
        this.jsonMapper = jsonMapper;
        this.systemInstruction = systemInstruction;
        this.developerInstruction = developerInstruction;
    }

    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public ObjectNode evidenceStorageUnavailable() {
        return jsonMapper.createObjectNode().put("error", "Could not confirm acceptance in PostgreSQL. No model call was made.");
    }

    @PostMapping(path = "/api/requests", consumes = "application/json", produces = "application/json;charset=UTF-8")
    @ResponseStatus(HttpStatus.CREATED)
    public ObjectNode submitRequest(@RequestBody JsonNode submission) throws GeneralSecurityException {
        JsonNode userText = submission.path("text");
        if (!submission.isObject() || submission.size() != 1 || !userText.isString() || userText.asString().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Expected one non-blank text field");
        }

        List<InputMessage> finalMessages = new ArrayList<>();
        finalMessages.add(new InputMessage(MessageRole.SYSTEM, systemInstruction));
        if (!developerInstruction.isBlank()) {
            finalMessages.add(new InputMessage(MessageRole.DEVELOPER, developerInstruction));
        }
        try {
            finalMessages.add(new InputMessage(MessageRole.USER, userText.asString()));
        } catch (IllegalArgumentException invalidMessage) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid user message");
        }

        ProcessedRequest processedRequest = requestService.acceptAndCallModel(finalMessages);
        var signedReceipt = processedRequest.signedReceipt();
        ObjectNode result = jsonMapper.createObjectNode();
        result.put("acceptedRequestId", signedReceipt.receiptBody().acceptedRequestId().toString());
        result.set("signedReceipt", jsonMapper.readTree(CanonicalReceiptEncoder.encodeSignedReceipt(signedReceipt)));
        result.set("modelExecution", jsonMapper.valueToTree(processedRequest.modelExecution()));
        result.put("modelOutcomeSaved", processedRequest.modelOutcomeSaved());
        return result;
    }
}
