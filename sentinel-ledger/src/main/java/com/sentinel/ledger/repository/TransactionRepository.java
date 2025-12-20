package com.sentinel.ledger.repository;

import com.sentinel.ledger.entity.Transaction;
import com.sentinel.ledger.entity.TransactionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Spring Data JPA repository for Transaction entity.
 * Provides CRUD operations and custom query methods for transaction management.
 */
@Repository
public interface TransactionRepository extends JpaRepository<Transaction, UUID> {

    /**
     * Find all transactions for a specific customer
     */
    List<Transaction> findByCustomerId(String customerId);

    /**
     * Find all transactions for a specific merchant
     */
    List<Transaction> findByMerchantId(String merchantId);

    /**
     * Find all transactions with a specific status
     */
    List<Transaction> findByStatus(TransactionStatus status);

    /**
     * Find transactions for a customer with a specific status
     */
    List<Transaction> findByCustomerIdAndStatus(String customerId, TransactionStatus status);

    /**
     * Find transactions within a time range
     */
    List<Transaction> findByTimestampBetween(Instant start, Instant end);

    /**
     * Count transactions by status
     */
    long countByStatus(TransactionStatus status);

    /**
     * Find high-risk transactions (risk score above threshold)
     */
    @Query("SELECT t FROM Transaction t WHERE t.riskScore >= :threshold ORDER BY t.riskScore DESC")
    List<Transaction> findHighRiskTransactions(@Param("threshold") java.math.BigDecimal threshold);

    /**
     * Find recent transactions for a customer (for velocity checks)
     */
    @Query("SELECT t FROM Transaction t WHERE t.customerId = :customerId AND t.timestamp >= :since ORDER BY t.timestamp DESC")
    List<Transaction> findRecentTransactionsByCustomer(
            @Param("customerId") String customerId,
            @Param("since") Instant since);

    /**
     * Find transaction by client reference ID for idempotency check
     */
    java.util.Optional<Transaction> findByClientReferenceId(String clientReferenceId);
}
