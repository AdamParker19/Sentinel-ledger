package com.sentinel.ledger.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Transaction entity representing a financial transaction in the ledger.
 * 
 * Transactions are created with PENDING status and updated based on
 * risk assessment from the sentinel-risk-engine service.
 */
@Entity
@Table(name = "transactions", indexes = {
        @Index(name = "idx_transaction_customer", columnList = "customerId"),
        @Index(name = "idx_transaction_merchant", columnList = "merchantId"),
        @Index(name = "idx_transaction_status", columnList = "status"),
        @Index(name = "idx_transaction_timestamp", columnList = "timestamp")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Transaction {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /**
     * Transaction amount in the base currency
     */
    @NotNull(message = "Amount is required")
    @Positive(message = "Amount must be positive")
    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    /**
     * Currency code (ISO 4217)
     */
    @NotNull(message = "Currency is required")
    @Column(nullable = false, length = 3)
    @Builder.Default
    private String currency = "USD";

    /**
     * Unique identifier for the merchant
     */
    @NotNull(message = "Merchant ID is required")
    @Column(nullable = false, length = 50)
    private String merchantId;

    /**
     * Unique identifier for the customer
     */
    @NotNull(message = "Customer ID is required")
    @Column(nullable = false, length = 50)
    private String customerId;

    /**
     * Current status of the transaction in the fraud detection pipeline
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private TransactionStatus status = TransactionStatus.PENDING;

    /**
     * Risk score assigned by the ML model (0.0 - 1.0, higher = more risky)
     */
    @Column(precision = 5, scale = 4)
    private BigDecimal riskScore;

    /**
     * Timestamp when the transaction was created
     */
    @Column(nullable = false, updatable = false)
    @Builder.Default
    private Instant timestamp = Instant.now();

    /**
     * Timestamp when the transaction was last updated
     */
    @Column
    private Instant updatedAt;

    /**
     * Optional description or memo for the transaction
     */
    @Column(length = 500)
    private String description;

    /**
     * IP address from which the transaction originated
     */
    @Column(length = 45)
    private String sourceIp;

    /**
     * Geographical location code (country ISO)
     */
    @Column(length = 2)
    private String locationCode;

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = Instant.now();
    }
}
