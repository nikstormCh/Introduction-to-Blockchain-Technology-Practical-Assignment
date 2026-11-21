BEGIN;

CREATE TABLE IF NOT EXISTS proof_of_prompt.model_executions (
    accepted_request_id uuid PRIMARY KEY REFERENCES proof_of_prompt.accepted_requests,
    execution_status text NOT NULL DEFAULT 'NOT_STARTED',
    model_response_utf8 bytea,
    failure_description text,
    CHECK (
        (execution_status = 'NOT_STARTED' AND model_response_utf8 IS NULL AND failure_description IS NULL)
        OR (execution_status = 'SUCCEEDED' AND model_response_utf8 IS NOT NULL AND failure_description IS NULL)
        OR (execution_status = 'FAILED' AND model_response_utf8 IS NULL AND failure_description IS NOT NULL)
    )
);

-- Step 3 receipts predate model execution.
INSERT INTO proof_of_prompt.model_executions (accepted_request_id)
SELECT accepted_request_id FROM proof_of_prompt.accepted_requests
ON CONFLICT (accepted_request_id) DO NOTHING;

GRANT SELECT, INSERT ON proof_of_prompt.model_executions TO :"app_role";
GRANT UPDATE (execution_status, model_response_utf8, failure_description)
    ON proof_of_prompt.model_executions TO :"app_role";
COMMIT;
