package com.sentinel.ledger.listener;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinel.ledger.entity.TransactionStatus;
import com.sentinel.ledger.service.TransactionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Kafka listener for consuming scored transaction results from the Risk Engine.
 * 
 * This completes the feedback loop:
 * 1. Ledger creates transaction (PENDING) → publishes to txn.created
 * 2. Risk Engine scores → publishes to txn.scored
 * 3. This listener consumes txn.scored → updates transaction status
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TransactionResultListener {

    private final TransactionService transactionService;
    private final ObjectMapper objectMapper;

    /**
     * Consume scored transaction events and update the database.
     * 
     * Expected message format from Python Risk Engine:
     * {
     *   "transactionId": "uuid-string",
     *   "riskScore": 0.1234,
     *   "isAnomaly": false,
     *   "recommendation": "APPROVE" | "REJECT",
     *   "scoredAt": "2024-01-01T12:00:00Z"
     * }
     */
    @KafkaListener(
            topics = "${sentinel.kafka.topic.transaction-scored}",
            groupId = "${spring.kafka.consumer.group-id}"
    )
    public void handleTransactionResult(String message) {
        log.info("Received scored transaction event: {}", message);

        try {
            JsonNode json = objectMapper.readTree(message);

            // Extract fields from the message
            String transactionIdStr = json.get("transactionId").asText();
            UUID transactionId = UUID.fromString(transactionIdStr);
            
            double riskScoreValue = json.get("riskScore").asDouble();
            BigDecimal riskScore = BigDecimal.valueOf(riskScoreValue);
            
            boolean isAnomaly = json.get("isAnomaly").asBoolean();
            String recommendation = json.get("recommendation").asText();

            // Determine status based on recommendation
            TransactionStatus newStatus = "REJECT".equalsIgnoreCase(recommendation)
                    ? TransactionStatus.REJECTED
                    : TransactionStatus.APPROVED;

            log.info("Updating transaction {}: riskScore={}, isAnomaly={}, status={}",
                    transactionId, riskScore, isAnomaly, newStatus);

            // Update the transaction in the database
            transactionService.updateTransactionStatus(transactionId, newStatus, riskScore);

            log.info("Transaction {} successfully updated to {}", transactionId, newStatus);

        } catch (Exception e) {
            log.error("Failed to process scored transaction event: {}", message, e);
            // In production, consider dead-letter queue or retry mechanism
        }
    }
}
