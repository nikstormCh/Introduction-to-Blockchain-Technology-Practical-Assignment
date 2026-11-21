package ee.ut.proofofprompt.proof_of_prompt.persistence;

import ee.ut.proofofprompt.proof_of_prompt.crypto.CommittedRequest;
import ee.ut.proofofprompt.proof_of_prompt.crypto.MessageRole;
import ee.ut.proofofprompt.proof_of_prompt.crypto.PromptMessage;
import ee.ut.proofofprompt.proof_of_prompt.crypto.RequestAcceptanceReceiptBody;
import ee.ut.proofofprompt.proof_of_prompt.crypto.RequestAcceptanceStatus;
import ee.ut.proofofprompt.proof_of_prompt.crypto.SignedRequestAcceptanceReceipt;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class AcceptedRequestEvidenceRepository {
    private final JdbcTemplate database;

    public AcceptedRequestEvidenceRepository(JdbcTemplate database) {
        this.database = database;
    }

    @Transactional
    public void saveAcceptedRequestEvidence(AcceptedRequestEvidence evidence) {
        SignedRequestAcceptanceReceipt signedReceipt = evidence.signedReceipt();
        RequestAcceptanceReceiptBody receiptBody = signedReceipt.receiptBody();
        database.update("""
                INSERT INTO proof_of_prompt.accepted_requests
                    (accepted_request_id, receipt_id, receipt_protocol_version, selected_model_id_utf8,
                     request_messages_merkle_root, message_count_in_request, request_acceptance_status,
                     request_accepted_at_utc, receipt_signing_key_id_utf8, receipt_signature)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, receiptBody.acceptedRequestId(), receiptBody.receiptId(), receiptBody.receiptProtocolVersion(),
                receiptBody.selectedModelId().getBytes(StandardCharsets.UTF_8), receiptBody.requestMessagesMerkleRoot(),
                receiptBody.messageCountInRequest(), receiptBody.requestAcceptanceStatus().name(),
                receiptBody.requestAcceptedAtUtc().toString(), receiptBody.receiptSigningKeyId().getBytes(StandardCharsets.UTF_8),
                Base64.getDecoder().decode(signedReceipt.receiptSignatureBase64()));

        List<PromptMessage> messages = evidence.committedRequest().messages();
        database.batchUpdate("""
                INSERT INTO proof_of_prompt.request_messages
                    (accepted_request_id, message_position, message_protocol_version,
                     message_role, message_content_utf8, secret_random_value)
                VALUES (?, ?, ?, ?, ?, ?)
                """, messages, 100, (statement, message) -> {
            statement.setObject(1, receiptBody.acceptedRequestId());
            statement.setInt(2, message.position());
            statement.setInt(3, message.protocolVersion());
            statement.setString(4, message.role().name());
            statement.setBytes(5, message.content().getBytes(StandardCharsets.UTF_8));
            statement.setBytes(6, HexFormat.of().parseHex(message.randomValue()));
        });
        database.update("INSERT INTO proof_of_prompt.model_executions (accepted_request_id) VALUES (?)",
                receiptBody.acceptedRequestId());
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Optional<AcceptedRequestEvidence> findAcceptedRequestEvidence(UUID acceptedRequestId) {
        List<SignedRequestAcceptanceReceipt> receipts = database.query("""
                SELECT accepted_request_id, receipt_id, receipt_protocol_version, selected_model_id_utf8,
                       request_messages_merkle_root, message_count_in_request, request_acceptance_status,
                       request_accepted_at_utc, receipt_signing_key_id_utf8, receipt_signature
                FROM proof_of_prompt.accepted_requests WHERE accepted_request_id = ?
                """, (row, rowNumber) -> readSignedReceipt(row), acceptedRequestId);
        if (receipts.isEmpty()) {
            return Optional.empty();
        }
        List<PromptMessage> orderedMessages = database.query("""
                SELECT message_position, message_protocol_version, message_role, message_content_utf8, secret_random_value
                FROM proof_of_prompt.request_messages WHERE accepted_request_id = ? ORDER BY message_position
                """, (row, rowNumber) -> new PromptMessage(row.getInt("message_protocol_version"),
                row.getInt("message_position"), MessageRole.valueOf(row.getString("message_role")),
                readUtf8(row, "message_content_utf8"), HexFormat.of().formatHex(row.getBytes("secret_random_value"))),
                acceptedRequestId);

        CommittedRequest restoredRequest = CommittedRequest.restore(acceptedRequestId, orderedMessages);
        return Optional.of(new AcceptedRequestEvidence(restoredRequest, receipts.getFirst()));
    }

    private static SignedRequestAcceptanceReceipt readSignedReceipt(ResultSet row) throws SQLException {
        var receiptBody = new RequestAcceptanceReceiptBody(row.getInt("receipt_protocol_version"),
                row.getObject("receipt_id", UUID.class), row.getObject("accepted_request_id", UUID.class),
                readUtf8(row, "selected_model_id_utf8"), row.getString("request_messages_merkle_root"),
                row.getInt("message_count_in_request"), RequestAcceptanceStatus.valueOf(row.getString("request_acceptance_status")),
                Instant.parse(row.getString("request_accepted_at_utc")), readUtf8(row, "receipt_signing_key_id_utf8"));
        return new SignedRequestAcceptanceReceipt(receiptBody, Base64.getEncoder().encodeToString(row.getBytes("receipt_signature")));
    }

    private static String readUtf8(ResultSet row, String columnName) throws SQLException {
        try {
            return StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(row.getBytes(columnName))).toString();
        } catch (java.nio.charset.CharacterCodingException invalidText) {
            throw new SQLException("Invalid UTF-8 in stored evidence column " + columnName, invalidText);
        }
    }
}
