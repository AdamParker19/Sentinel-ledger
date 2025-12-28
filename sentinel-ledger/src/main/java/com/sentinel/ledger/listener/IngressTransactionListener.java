package com.sentinel.ledger.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinel.ledger.dto.CreateTransactionRequest;
import com.sentinel.ledger.service.TransactionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.retrytopic.DltStrategy;
import org.springframework.retry.annotation.Backoff;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class IngressTransactionListener {

    private final TransactionService transactionService;
    private final ObjectMapper objectMapper;

    /**
     * @RetryableTopic:
     * 1. If this fails, wait 1 second, retry.
     * 2. If it fails again, wait 2 seconds, retry.
     * 3. If it fails 3 times, send to "raw.requests-dlt" (Dead Letter Topic).
     */
    @RetryableTopic(
        attempts = "3",
        backoff = @Backoff(delay = 1000, multiplier = 2.0),
        dltStrategy = DltStrategy.FAIL_ON_ERROR,
        kafkaTemplate = "kafkaTemplate"
    )
    @KafkaListener(topics = "${sentinel.kafka.topic.raw-requests:raw.requests}", groupId = "sentinel-ledger-ingress")
    public void processIngressTransaction(String rawJson) {
        log.info("⚡ Received Raw Ingress: {}", rawJson);
        try {
            CreateTransactionRequest request = objectMapper.readValue(rawJson, CreateTransactionRequest.class);
            transactionService.createTransaction(request);
            log.info("✅ Ingested: {}", request.getCustomerId());
        } catch (Exception e) {
            log.error("❌ Error processing: {}", rawJson, e);
            throw new RuntimeException(e); // Throwing ensures the Retry mechanism triggers
        }
    }
}
