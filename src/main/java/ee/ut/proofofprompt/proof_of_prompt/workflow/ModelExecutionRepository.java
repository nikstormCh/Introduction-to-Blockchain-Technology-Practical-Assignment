package ee.ut.proofofprompt.proof_of_prompt.workflow;

import org.springframework.dao.IncorrectUpdateSemanticsDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

@Repository
public class ModelExecutionRepository {
    private final JdbcTemplate database;

    public ModelExecutionRepository(JdbcTemplate database) {
        this.database = database;
    }

    @Transactional
    public void recordFinalOutcome(UUID acceptedRequestId, ModelExecutionOutcome outcome) {
        byte[] responseBytes = outcome.modelResponse() == null ? null : outcome.modelResponse().getBytes(StandardCharsets.UTF_8);
        int changedRows = database.update("""
                UPDATE proof_of_prompt.model_executions
                   SET execution_status = ?, model_response_utf8 = ?, failure_description = ?
                 WHERE accepted_request_id = ? AND execution_status = 'NOT_STARTED'
                """, outcome.executionStatus().name(), responseBytes, outcome.failureDescription(), acceptedRequestId);
        if (changedRows != 1) {
            throw new IncorrectUpdateSemanticsDataAccessException("No unfinished execution for the accepted request");
        }
    }
}
