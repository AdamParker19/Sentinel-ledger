package com.sentinel.ledger;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Sentinel Ledger Service - Financial Transaction Management
 * 
 * This service handles:
 * - Creating and persisting financial transactions
 * - Publishing transaction events to Kafka for downstream processing
 * - Updating transaction status based on risk scoring results
 */
@SpringBootApplication
public class LedgerApplication {

    public static void main(String[] args) {
        SpringApplication.run(LedgerApplication.class, args);
    }
}
