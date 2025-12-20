"""
Sentinel Risk Engine - FastAPI Application

This service consumes transaction events from Kafka, scores them using a
pre-trained Isolation Forest model, and provides REST endpoints for
manual scoring and health checks.

Usage:
    uvicorn main:app --host 0.0.0.0 --port 8001 --reload
"""

import asyncio
import json
import logging
import pickle
from contextlib import asynccontextmanager
from datetime import datetime
from pathlib import Path
from typing import Any

import numpy as np
from aiokafka import AIOKafkaConsumer, AIOKafkaProducer
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field
from pydantic_settings import BaseSettings

# =============================================================================
# Configuration
# =============================================================================

class Settings(BaseSettings):
    """Application settings loaded from environment variables."""
    
    kafka_bootstrap_servers: str = "localhost:9092"
    kafka_topic_txn_created: str = "txn.created"
    kafka_topic_txn_scored: str = "txn.scored"
    kafka_consumer_group: str = "sentinel-risk-engine"
    model_path: str = "model.pkl"
    
    class Config:
        env_prefix = "SENTINEL_"


settings = Settings()

# Configure logging
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s - %(name)s - %(levelname)s - %(message)s"
)
logger = logging.getLogger(__name__)


# =============================================================================
# Pydantic Models
# =============================================================================

class TransactionEvent(BaseModel):
    """Incoming transaction event from Kafka."""
    id: str
    clientReferenceId: str | None = Field(default=None, alias="clientReferenceId")
    amount: float
    currency: str = "USD"
    merchantId: str = Field(alias="merchantId")
    customerId: str = Field(alias="customerId")
    status: str = "PENDING"
    riskScore: float | None = Field(default=None, alias="riskScore")
    timestamp: float | str | None = None
    description: str | None = None
    sourceIp: str | None = Field(default=None, alias="sourceIp")
    locationCode: str | None = Field(default=None, alias="locationCode")

    class Config:
        populate_by_name = True


class RiskScoreRequest(BaseModel):
    """Request for manual risk scoring via REST API."""
    amount: float
    hour_of_day: int = Field(ge=0, le=23, default=12)
    day_of_week: int = Field(ge=0, le=6, default=2)
    merchant_category: int = Field(ge=0, le=9, default=0)
    customer_age_days: float = Field(ge=0, default=180)
    txn_frequency_1h: int = Field(ge=0, default=1)
    avg_amount_30d: float = Field(gt=0, default=100.0)


class RiskScoreResponse(BaseModel):
    """Response containing risk assessment."""
    transaction_id: str | None = None
    risk_score: float
    is_anomaly: bool
    recommendation: str
    scored_at: str


class HealthResponse(BaseModel):
    """Health check response."""
    status: str
    model_loaded: bool
    kafka_connected: bool
    timestamp: str


# =============================================================================
# Global State
# =============================================================================

class AppState:
    """Application state container."""
    model: Any = None
    scaler: Any = None
    feature_columns: list = []
    consumer: AIOKafkaConsumer | None = None
    producer: AIOKafkaProducer | None = None
    consumer_task: asyncio.Task | None = None


state = AppState()


# =============================================================================
# Model Loading & Scoring
# =============================================================================

def load_model(model_path: str) -> bool:
    """Load the trained model from pickle file."""
    path = Path(model_path)
    
    if not path.exists():
        logger.warning(f"Model file not found: {path.absolute()}")
        logger.warning("Run 'python train.py' to generate the model first.")
        return False
    
    try:
        with open(path, "rb") as f:
            bundle = pickle.load(f)
        
        state.model = bundle["model"]
        state.scaler = bundle["scaler"]
        state.feature_columns = bundle["feature_columns"]
        
        logger.info(f"Model loaded successfully from: {path.absolute()}")
        logger.info(f"Model version: {bundle.get('version', 'unknown')}")
        logger.info(f"Feature columns: {state.feature_columns}")
        return True
        
    except Exception as e:
        logger.error(f"Failed to load model: {e}")
        return False


def score_transaction(event: TransactionEvent) -> tuple[float, bool]:
    """
    Score a transaction using the loaded model.
    
    Returns:
        tuple: (risk_score, is_anomaly)
    """
    if state.model is None:
        raise RuntimeError("Model not loaded")
    
    # Extract features from event
    # In production, these would be computed from historical data
    if event.timestamp is None:
        hour = datetime.now().hour
    elif isinstance(event.timestamp, (int, float)):
        # Unix epoch timestamp (from Java Instant)
        hour = datetime.fromtimestamp(event.timestamp).hour
    else:
        # ISO format string
        hour = datetime.fromisoformat(event.timestamp.replace("Z", "+00:00")).hour
    
    features = np.array([[
        event.amount,
        hour,
        datetime.now().weekday(),  # day_of_week
        hash(event.merchantId) % 10,  # merchant_category (simplified)
        180,  # customer_age_days (placeholder)
        1,    # txn_frequency_1h (placeholder)
        event.amount * 0.8,  # avg_amount_30d (placeholder)
    ]])
    
    # Scale features
    features_scaled = state.scaler.transform(features)
    
    # Get anomaly score (lower = more anomalous)
    decision_score = state.model.decision_function(features_scaled)[0]
    prediction = state.model.predict(features_scaled)[0]
    
    # Convert decision score to risk score (0-1, higher = more risky)
    # Isolation Forest decision_function returns negative for anomalies
    risk_score = max(0.0, min(1.0, 0.5 - (decision_score * 0.5)))
    
    # Convert numpy bool to Python bool for JSON serialization
    is_anomaly = bool(prediction == -1)
    
    return risk_score, is_anomaly


def score_features(request: RiskScoreRequest) -> tuple[float, bool]:
    """Score a transaction using explicit features."""
    if state.model is None:
        raise RuntimeError("Model not loaded")
    
    features = np.array([[
        request.amount,
        request.hour_of_day,
        request.day_of_week,
        request.merchant_category,
        request.customer_age_days,
        request.txn_frequency_1h,
        request.avg_amount_30d,
    ]])
    
    features_scaled = state.scaler.transform(features)
    decision_score = state.model.decision_function(features_scaled)[0]
    prediction = state.model.predict(features_scaled)[0]
    
    risk_score = max(0.0, min(1.0, 0.5 - (decision_score * 0.5)))
    is_anomaly = bool(prediction == -1)
    
    return risk_score, is_anomaly


# =============================================================================
# Kafka Consumer
# =============================================================================

async def consume_transactions():
    """Background task to consume and score transactions from Kafka."""
    logger.info("Starting Kafka consumer...")
    
    try:
        state.consumer = AIOKafkaConsumer(
            settings.kafka_topic_txn_created,
            bootstrap_servers=settings.kafka_bootstrap_servers,
            group_id=settings.kafka_consumer_group,
            auto_offset_reset="earliest",
            enable_auto_commit=True,
            value_deserializer=lambda m: json.loads(m.decode("utf-8"))
        )
        
        state.producer = AIOKafkaProducer(
            bootstrap_servers=settings.kafka_bootstrap_servers,
            value_serializer=lambda v: json.dumps(v).encode("utf-8")
        )
        
        await state.consumer.start()
        await state.producer.start()
        logger.info(f"Kafka consumer connected, listening on topic: {settings.kafka_topic_txn_created}")
        
        async for message in state.consumer:
            try:
                logger.info(f"Received message: partition={message.partition}, offset={message.offset}")
                logger.info(f"Raw message value: {message.value}")
                
                # Parse transaction event
                event = TransactionEvent(**message.value)
                logger.info(f"Processing transaction: {event.id}, amount: {event.amount}")
                
                # Score the transaction
                risk_score, is_anomaly = score_transaction(event)
                
                recommendation = "REJECT" if is_anomaly else "APPROVE"
                logger.info(f"Transaction {event.id}: risk_score={risk_score:.4f}, anomaly={is_anomaly}, recommendation={recommendation}")
                
                # Publish scored event
                scored_event = {
                    "transactionId": event.id,
                    "riskScore": round(risk_score, 4),
                    "isAnomaly": is_anomaly,
                    "recommendation": recommendation,
                    "scoredAt": datetime.utcnow().isoformat() + "Z"
                }
                
                await state.producer.send_and_wait(
                    settings.kafka_topic_txn_scored,
                    value=scored_event,
                    key=event.id.encode("utf-8")
                )
                logger.info(f"Published scored event for transaction: {event.id}")
                
            except Exception as e:
                import traceback
                error_msg = f"Error processing message: {e}\nTraceback:\n{traceback.format_exc()}\nMessage: {message.value}"
                logger.error(error_msg)
                # Also write to file for debugging
                with open("consumer_errors.log", "a") as f:
                    f.write(f"\n{'='*60}\n{error_msg}\n")
                
    except asyncio.CancelledError:
        logger.info("Consumer task cancelled")
    except Exception as e:
        logger.error(f"Consumer error: {e}", exc_info=True)
    finally:
        if state.consumer:
            await state.consumer.stop()
        if state.producer:
            await state.producer.stop()
        logger.info("Kafka consumer stopped")


# =============================================================================
# Application Lifespan
# =============================================================================

@asynccontextmanager
async def lifespan(app: FastAPI):
    """Application lifespan handler - startup and shutdown."""
    logger.info("=" * 60)
    logger.info("Sentinel Risk Engine - Starting up")
    logger.info("=" * 60)
    
    # Load ML model
    model_loaded = load_model(settings.model_path)
    
    # Start Kafka consumer if model is loaded
    if model_loaded:
        state.consumer_task = asyncio.create_task(consume_transactions())
    else:
        logger.warning("Kafka consumer not started - model not loaded")
    
    yield  # Application is running
    
    # Shutdown
    logger.info("Shutting down...")
    if state.consumer_task:
        state.consumer_task.cancel()
        try:
            await state.consumer_task
        except asyncio.CancelledError:
            pass
    logger.info("Shutdown complete")


# =============================================================================
# FastAPI Application
# =============================================================================

app = FastAPI(
    title="Sentinel Risk Engine",
    description="ML-powered fraud detection service using Isolation Forest",
    version="1.0.0",
    lifespan=lifespan
)


@app.get("/health", response_model=HealthResponse)
async def health_check():
    """Health check endpoint."""
    return HealthResponse(
        status="healthy" if state.model is not None else "degraded",
        model_loaded=state.model is not None,
        kafka_connected=state.consumer is not None and state.consumer._closed is False if state.consumer else False,
        timestamp=datetime.utcnow().isoformat() + "Z"
    )


@app.post("/api/v1/score", response_model=RiskScoreResponse)
async def score_transaction_api(request: RiskScoreRequest):
    """
    Score a transaction manually via REST API.
    
    This endpoint allows direct risk scoring without going through Kafka.
    """
    if state.model is None:
        raise HTTPException(status_code=503, detail="Model not loaded. Run 'python train.py' first.")
    
    try:
        risk_score, is_anomaly = score_features(request)
        
        return RiskScoreResponse(
            transaction_id=None,
            risk_score=round(risk_score, 4),
            is_anomaly=is_anomaly,
            recommendation="REJECT" if is_anomaly else "APPROVE",
            scored_at=datetime.utcnow().isoformat() + "Z"
        )
    except Exception as e:
        logger.error(f"Scoring error: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=str(e))


@app.get("/api/v1/model/info")
async def model_info():
    """Get information about the loaded model."""
    if state.model is None:
        raise HTTPException(status_code=503, detail="Model not loaded")
    
    return {
        "feature_columns": state.feature_columns,
        "contamination": state.model.contamination,
        "n_estimators": state.model.n_estimators,
    }


# =============================================================================
# Entry Point
# =============================================================================

if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=8001, reload=True)
