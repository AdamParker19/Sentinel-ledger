package com.sentinel.ledger.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * DTO for creating a new transaction request.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateTransactionRequest {

    @NotNull(message = "Amount is required")
    @Positive(message = "Amount must be positive")
    private BigDecimal amount;

    @Builder.Default
    private String currency = "USD";

    @NotNull(message = "Merchant ID is required")
    private String merchantId;

    @NotNull(message = "Customer ID is required")
    private String customerId;

    private String description;

    private String sourceIp;

    private String locationCode;

    /**
     * Client-provided idempotency key to prevent duplicate transactions.
     * If provided, duplicate requests with the same key return the existing
     * transaction.
     */
    private String clientReferenceId;
}
