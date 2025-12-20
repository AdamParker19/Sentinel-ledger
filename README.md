# 🛡️ Sentinel - Event-Driven Fraud Detection System

[![Java](https://img.shields.io/badge/Java-17-orange?style=flat-square&logo=openjdk)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.2-green?style=flat-square&logo=spring)](https://spring.io/projects/spring-boot)
[![Python](https://img.shields.io/badge/Python-3.10+-blue?style=flat-square&logo=python)](https://www.python.org/)
[![FastAPI](https://img.shields.io/badge/FastAPI-0.109-teal?style=flat-square&logo=fastapi)](https://fastapi.tiangolo.com/)
[![Kafka](https://img.shields.io/badge/Apache%20Kafka-7.5-black?style=flat-square&logo=apachekafka)](https://kafka.apache.org/)
[![License](https://img.shields.io/badge/License-MIT-yellow?style=flat-square)](LICENSE)

A **polyglot microservices architecture** for real-time fraud detection, combining Java/Spring Boot for transaction processing, Python/FastAPI with machine learning for risk scoring, and Apache Kafka for event streaming.

---

## 📐 Architecture

```
┌─────────────────────┐         ┌──────────────────┐         ┌─────────────────────┐
│   sentinel-ledger   │         │   Apache Kafka   │         │ sentinel-risk-engine│
│  (Java/Spring Boot) │────────▶│                  │────────▶│  (Python/FastAPI)   │
│                     │         │   txn.created    │         │                     │
│  • REST API         │         │   txn.scored     │         │  • ML Scoring       │
│  • PostgreSQL       │         │                  │◀────────│  • Isolation Forest │
│  • Kafka Producer   │         └──────────────────┘         │  • Async Consumer   │
└─────────┬───────────┘                                      └─────────────────────┘
          │
          ▼
┌─────────────────────┐
│     PostgreSQL      │
│  (Transaction DB)   │
└─────────────────────┘
```

### Event Flow

1. **Transaction Created** → `sentinel-ledger` receives a POST request, saves transaction as `PENDING`, publishes to `txn.created` topic
2. **Risk Scoring** → `sentinel-risk-engine` consumes the event, runs through Isolation Forest ML model
3. **Score Published** → Risk score and recommendation (`APPROVE`/`REJECT`) published to `txn.scored` topic

---

## 🚀 Quick Start

### Prerequisites

- **Docker & Docker Compose** (for infrastructure)
- **Java 17** (for ledger service)
- **Python 3.10+** (for risk engine)
- **Maven** (or use included wrapper)

### 1. Clone the Repository

```bash
git clone https://github.com/AdamParker19/Sentinel-ledger.git
cd Sentinel-ledger
```

### 2. Start Infrastructure

```bash
docker-compose up -d
```

This starts:
- **Zookeeper** (port 2181)
- **Kafka** (port 9092)
- **PostgreSQL** (port 5432)

### 3. Train the ML Model

```bash
cd sentinel-risk-engine
pip install -r requirements.txt
python train.py
```

This generates `model.pkl` - an Isolation Forest trained on synthetic transaction data.

### 4. Start the Risk Engine

```bash
uvicorn main:app --host 0.0.0.0 --port 8001
```

### 5. Start the Ledger Service

```bash
cd sentinel-ledger
./mvnw spring-boot:run
```

---

## 📁 Project Structure

```
sentinel/
├── docker-compose.yml              # Infrastructure (Kafka, Zookeeper, PostgreSQL)
├── README.md
│
├── sentinel-ledger/                # Java Spring Boot Service
│   ├── pom.xml
│   └── src/main/
│       ├── java/com/sentinel/ledger/
│       │   ├── LedgerApplication.java
│       │   ├── config/KafkaProducerConfig.java
│       │   ├── controller/TransactionController.java
│       │   ├── dto/
│       │   ├── entity/Transaction.java
│       │   ├── repository/TransactionRepository.java
│       │   └── service/TransactionService.java
│       └── resources/application.yml
│
└── sentinel-risk-engine/           # Python FastAPI Service
    ├── requirements.txt
    ├── train.py                    # ML model training script
    └── main.py                     # FastAPI application
```

---

## 🔌 API Endpoints

### Ledger Service (Port 8080)

| Method | Endpoint | Description |
|--------|----------|-------------|
| `POST` | `/api/v1/transactions` | Create a new transaction |
| `GET` | `/api/v1/transactions/{id}` | Get transaction by ID |
| `GET` | `/api/v1/transactions/customer/{id}` | Get customer transactions |
| `GET` | `/api/v1/transactions/pending` | Get pending transactions |

#### Create Transaction Example

```bash
curl -X POST http://localhost:8080/api/v1/transactions \
  -H "Content-Type: application/json" \
  -d '{
    "amount": 150.00,
    "currency": "USD",
    "merchantId": "MERCH-001",
    "customerId": "CUST-12345",
    "description": "Online purchase"
  }'
```

### Risk Engine (Port 8001)

| Method | Endpoint | Description |
|--------|----------|-------------|
| `GET` | `/health` | Health check |
| `POST` | `/api/v1/score` | Manual risk scoring |
| `GET` | `/api/v1/model/info` | Model information |

---

## ⚙️ Configuration

### Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `SENTINEL_KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | Kafka bootstrap servers |
| `SENTINEL_MODEL_PATH` | `model.pkl` | Path to trained model |

### Database Credentials

| Property | Value |
|----------|-------|
| Host | `localhost:5432` |
| Database | `sentinel_ledger` |
| Username | `sentinel` |
| Password | `sentinel123` |

### Kafka Topics

| Topic | Description |
|-------|-------------|
| `txn.created` | New transactions awaiting risk assessment |
| `txn.scored` | Transactions with risk scores and recommendations |

---

## 🧠 Machine Learning Model

The risk engine uses an **Isolation Forest** algorithm for anomaly detection:

- **Features**: amount, hour_of_day, day_of_week, merchant_category, customer_age_days, txn_frequency_1h, avg_amount_30d
- **Contamination**: 5% (expected anomaly ratio)
- **Output**: Risk score (0.0-1.0) and binary anomaly flag

Run `python train.py` to retrain on new data.

---

## 🛠️ Development

### Building the Ledger Service

```bash
cd sentinel-ledger
./mvnw clean package -DskipTests
```

### Running Tests

```bash
# Java tests
cd sentinel-ledger && ./mvnw test

# Python tests
cd sentinel-risk-engine && pytest
```

---

## 📝 License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

---

## 🤝 Contributing

1. Fork the repository
2. Create a feature branch (`git checkout -b feature/amazing-feature`)
3. Commit your changes (`git commit -m 'Add amazing feature'`)
4. Push to the branch (`git push origin feature/amazing-feature`)
5. Open a Pull Request
