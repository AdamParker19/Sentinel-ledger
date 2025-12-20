package com.sentinel.ledger.controller;

import com.sentinel.ledger.dto.CreateTransactionRequest;
import com.sentinel.ledger.dto.TransactionEvent;
import com.sentinel.ledger.service.TransactionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * REST controller for transaction management.
 * Provides endpoints for creating and querying transactions.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/transactions")
@RequiredArgsConstructor
public class TransactionController {

    private final TransactionService transactionService;

    /**
     * Create a new transaction.
     * The transaction is saved as PENDING and published to Kafka for risk
     * assessment.
     */
    @PostMapping
    public ResponseEntity<TransactionEvent> createTransaction(
            @Valid @RequestBody CreateTransactionRequest request) {
        log.info("Received create transaction request for customer: {}", request.getCustomerId());
        TransactionEvent created = transactionService.createTransaction(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /**
     * Get a transaction by ID
     */
    @GetMapping("/{id}")
    public ResponseEntity<TransactionEvent> getTransaction(@PathVariable UUID id) {
        log.info("Fetching transaction: {}", id);
        TransactionEvent transaction = transactionService.getTransaction(id);
        return ResponseEntity.ok(transaction);
    }

    /**
     * Get all transactions for a customer
     */
    @GetMapping("/customer/{customerId}")
    public ResponseEntity<List<TransactionEvent>> getTransactionsByCustomer(
            @PathVariable String customerId) {
        log.info("Fetching transactions for customer: {}", customerId);
        List<TransactionEvent> transactions = transactionService.getTransactionsByCustomer(customerId);
        return ResponseEntity.ok(transactions);
    }

    /**
     * Get all pending transactions (for monitoring/debugging)
     */
    @GetMapping("/pending")
    public ResponseEntity<List<TransactionEvent>> getPendingTransactions() {
        log.info("Fetching all pending transactions");
        List<TransactionEvent> pending = transactionService.getPendingTransactions();
        return ResponseEntity.ok(pending);
    }

    /**
     * Health check endpoint
     */
    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("OK");
    }
}
