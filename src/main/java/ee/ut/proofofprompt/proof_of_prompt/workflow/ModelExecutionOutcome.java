package ee.ut.proofofprompt.proof_of_prompt.workflow;

public record ModelExecutionOutcome(ModelExecutionStatus executionStatus, String modelResponse, String failureDescription) {
    public static ModelExecutionOutcome succeeded(String modelResponse) {
        return new ModelExecutionOutcome(ModelExecutionStatus.SUCCEEDED, modelResponse, null);
    }

    public static ModelExecutionOutcome failed(String failureDescription) {
        return new ModelExecutionOutcome(ModelExecutionStatus.FAILED, null, failureDescription);
    }
}
