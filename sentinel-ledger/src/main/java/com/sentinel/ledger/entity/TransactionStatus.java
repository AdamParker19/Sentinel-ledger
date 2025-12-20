package com.sentinel.ledger.entity;

/**
 * Transaction status enumeration for fraud detection workflow.
 */
public enum TransactionStatus {

    /**
     * Initial status when transaction is created, awaiting risk assessment
     */
    PENDING,

    /**
     * Transaction approved after passing risk checks
     */
    APPROVED,

    /**
     * Transaction rejected due to high-risk score or fraud detection
     */
    REJECTED
}
