package com.sentinel.ledger.service;

import com.sentinel.ledger.dto.CreateTransactionRequest;
import com.sentinel.ledger.dto.TransactionEvent;
import com.sentinel.ledger.entity.Transaction;
import com.sentinel.ledger.entity.TransactionStatus;
import com.sentinel.ledger.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Service for managing financial transactions.
 * Handles transaction creation, persistence, and event publishing.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TransactionService {

    private final TransactionRepository transactionRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    @Value("${sentinel.kafka.topic.transaction-created}")
    private String transactionCreatedTopic;

    /**
     * Create a new transaction.
     * 1. Check for existing transaction with same clientReferenceId (idempotency)
     * 2. Persist with PENDING status
     * 3. Publish event to Kafka for risk assessment
     */
    @Transactional
    public TransactionEvent createTransaction(CreateTransactionRequest request) {
        log.info("Creating transaction for customer: {}, merchant: {}, amount: {}",
                request.getCustomerId(), request.getMerchantId(), request.getAmount());

        // Idempotency check: return existing transaction if clientReferenceId already
        // exists
        if (request.getClientReferenceId() != null && !request.getClientReferenceId().isBlank()) {
            var existing = transactionRepository.findByClientReferenceId(request.getClientReferenceId());
            if (existing.isPresent()) {
                log.info("Idempotency hit: returning existing transaction {} for clientReferenceId: {}",
                        existing.get().getId(), request.getClientReferenceId());
                return toEvent(existing.get());
            }
        }

        // Build and persist transaction entity
        Transaction transaction = Transaction.builder()
                .amount(request.getAmount())
                .currency(request.getCurrency())
                .merchantId(request.getMerchantId())
                .customerId(request.getCustomerId())
                .description(request.getDescription())
                .sourceIp(request.getSourceIp())
                .locationCode(request.getLocationCode())
                .clientReferenceId(request.getClientReferenceId())
                .status(TransactionStatus.PENDING)
                .build();

        Transaction saved = transactionRepository.save(transaction);
        log.info("Transaction persisted with ID: {}", saved.getId());

        // Convert to event DTO
        TransactionEvent event = toEvent(saved);

        // Publish to Kafka (async, non-blocking)
        publishTransactionCreatedEvent(event);

        return event;
    }

    /**
     * Update transaction status after risk assessment
     */
    @Transactional
    public TransactionEvent updateTransactionStatus(UUID transactionId, TransactionStatus status,
            BigDecimal riskScore) {
        log.info("Updating transaction {} with status: {}, riskScore: {}", transactionId, status, riskScore);

        Transaction transaction = transactionRepository.findById(transactionId)
                .orElseThrow(() -> new IllegalArgumentException("Transaction not found: " + transactionId));

        transaction.setStatus(status);
        transaction.setRiskScore(riskScore);

        Transaction updated = transactionRepository.save(transaction);
        return toEvent(updated);
    }

    /**
     * Get transaction by ID
     */
    public TransactionEvent getTransaction(UUID transactionId) {
        return transactionRepository.findById(transactionId)
                .map(this::toEvent)
                .orElseThrow(() -> new IllegalArgumentException("Transaction not found: " + transactionId));
    }

    /**
     * Get all transactions for a customer
     */
    public List<TransactionEvent> getTransactionsByCustomer(String customerId) {
        return transactionRepository.findByCustomerId(customerId)
                .stream()
                .map(this::toEvent)
                .toList();
    }

    /**
     * Get all pending transactions
     */
    public List<TransactionEvent> getPendingTransactions() {
        return transactionRepository.findByStatus(TransactionStatus.PENDING)
                .stream()
                .map(this::toEvent)
                .toList();
    }

    /**
     * Publish transaction created event to Kafka
     */
    private void publishTransactionCreatedEvent(TransactionEvent event) {
        CompletableFuture<SendResult<String, Object>> future = kafkaTemplate.send(transactionCreatedTopic,
                event.getId().toString(), event);

        future.whenComplete((result, ex) -> {
            if (ex == null) {
                log.info("Published transaction event to topic '{}', partition: {}, offset: {}",
                        transactionCreatedTopic,
                        result.getRecordMetadata().partition(),
                        result.getRecordMetadata().offset());
            } else {
                log.error("Failed to publish transaction event for ID: {}", event.getId(), ex);
            }
        });
    }

    /**
     * Convert entity to event DTO
     */
    private TransactionEvent toEvent(Transaction transaction) {
        return TransactionEvent.builder()
                .id(transaction.getId())
                .clientReferenceId(transaction.getClientReferenceId())
                .amount(transaction.getAmount())
                .currency(transaction.getCurrency())
                .merchantId(transaction.getMerchantId())
                .customerId(transaction.getCustomerId())
                .status(transaction.getStatus())
                .riskScore(transaction.getRiskScore())
                .timestamp(transaction.getTimestamp())
                .description(transaction.getDescription())
                .sourceIp(transaction.getSourceIp())
                .locationCode(transaction.getLocationCode())
                .build();
    }
}
