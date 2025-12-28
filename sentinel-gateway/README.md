# Sentinel Gateway

High-performance HTTP ingestion gateway for the Sentinel Fraud Detection system.

## Performance Target
- **10,000+ requests/second**
- Sub-millisecond latency to Kafka

## Tech Stack
- **Go 1.21+** with `gofiber/fiber` v2
- **IBM/sarama** AsyncProducer (fire-and-forget)
- **Distroless** Docker image (~10MB)

## API

### POST /api/v1/ingress
Accepts transaction payloads and pushes to Kafka `raw.requests` topic.

**Request:**
```json
{
  "amount": 150.00,
  "merchantId": "STORE-001",
  "customerId": "CUST-123",
  "clientReferenceId": "optional-idempotency-key",
  "currency": "USD",
  "description": "Purchase"
}
```

**Response (202 Accepted):**
```json
{
  "status": "ACCEPTED",
  "message": "Request queued for processing",
  "requestId": "uuid-here"
}
```

### GET /health
Health check endpoint.

## Running Locally

```bash
# Set environment
export KAFKA_BROKERS=localhost:9092
export INGRESS_TOPIC=raw.requests

# Run
go run main.go
```

## Docker Build

```bash
docker build -t sentinel-gateway .
docker run -p 3000:3000 -e KAFKA_BROKERS=kafka:29092 sentinel-gateway
```

## Configuration

| Variable | Default | Description |
|----------|---------|-------------|
| `KAFKA_BROKERS` | `localhost:9092` | Comma-separated broker list |
| `INGRESS_TOPIC` | `raw.requests` | Target Kafka topic |
| `PORT` | `3000` | HTTP listen port |
