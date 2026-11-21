package ee.ut.proofofprompt.proof_of_prompt.workflow;

import ee.ut.proofofprompt.proof_of_prompt.crypto.SignedRequestAcceptanceReceipt;

public record ProcessedRequest(SignedRequestAcceptanceReceipt signedReceipt, ModelExecutionOutcome modelExecution,
                               boolean modelOutcomeSaved) {}
