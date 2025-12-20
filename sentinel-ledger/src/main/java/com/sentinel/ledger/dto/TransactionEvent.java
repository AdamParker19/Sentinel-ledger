package com.sentinel.ledger.dto;

import com.sentinel.ledger.entity.TransactionStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * DTO for transaction response and Kafka event payload.
 * Used for both API responses and event publishing.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TransactionEvent {

    private UUID id;
    private BigDecimal amount;
    private String currency;
    private String merchantId;
    private String customerId;
    private TransactionStatus status;
    private BigDecimal riskScore;
    private Instant timestamp;
    private String description;
    private String sourceIp;
    private String locationCode;
}
