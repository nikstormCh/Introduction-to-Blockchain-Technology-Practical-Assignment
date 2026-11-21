BEGIN;

CREATE SCHEMA proof_of_prompt;
CREATE TABLE proof_of_prompt.accepted_requests (
    accepted_request_id uuid PRIMARY KEY,
    receipt_id uuid NOT NULL UNIQUE,
    receipt_protocol_version integer NOT NULL CHECK (receipt_protocol_version = 1),
    selected_model_id_utf8 bytea NOT NULL,
    request_messages_merkle_root text NOT NULL CHECK (request_messages_merkle_root ~ '^[0-9a-f]{64}$'),
    message_count_in_request integer NOT NULL CHECK (message_count_in_request > 0),
    request_acceptance_status text NOT NULL CHECK (request_acceptance_status = 'ACCEPTED'),
    request_accepted_at_utc text NOT NULL,
    receipt_signing_key_id_utf8 bytea NOT NULL,
    receipt_signature bytea NOT NULL CHECK (octet_length(receipt_signature) = 64)
);

CREATE TABLE proof_of_prompt.request_messages (
    accepted_request_id uuid NOT NULL REFERENCES proof_of_prompt.accepted_requests,
    message_position integer NOT NULL CHECK (message_position >= 0),
    message_protocol_version integer NOT NULL CHECK (message_protocol_version = 1),
    message_role text NOT NULL CHECK (message_role IN ('SYSTEM', 'DEVELOPER', 'USER', 'ASSISTANT')),
    message_content_utf8 bytea NOT NULL,
    secret_random_value bytea NOT NULL CHECK (octet_length(secret_random_value) = 32),
    PRIMARY KEY (accepted_request_id, message_position)
);
COMMIT;
