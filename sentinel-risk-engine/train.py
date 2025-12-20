"""
Sentinel Risk Engine - Model Training Script

This script generates synthetic transaction data and trains an Isolation Forest
model for anomaly detection. The trained model is saved as `model.pkl` for use
by the main FastAPI application.

Usage:
    python train.py

Output:
    - model.pkl: Trained Isolation Forest model
"""

import logging
import pickle
from pathlib import Path

import numpy as np
import pandas as pd
from sklearn.ensemble import IsolationForest
from sklearn.preprocessing import StandardScaler

# Configure logging
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s - %(levelname)s - %(message)s"
)
logger = logging.getLogger(__name__)


def generate_synthetic_data(n_samples: int = 10000, anomaly_ratio: float = 0.05) -> pd.DataFrame:
    """
    Generate synthetic transaction data for training.
    
    Features:
        - amount: Transaction amount (log-normal distribution)
        - hour_of_day: Hour when transaction occurred (0-23)
        - day_of_week: Day of week (0-6)
        - merchant_category: Encoded merchant category (0-9)
        - customer_age_days: Days since customer first transaction
        - txn_frequency_1h: Number of transactions in last hour
        - avg_amount_30d: Average transaction amount in last 30 days
    """
    logger.info(f"Generating {n_samples} synthetic transactions...")
    
    np.random.seed(42)
    
    n_normal = int(n_samples * (1 - anomaly_ratio))
    n_anomaly = n_samples - n_normal
    
    # Normal transactions
    normal_data = {
        "amount": np.random.lognormal(mean=4.0, sigma=1.0, size=n_normal),
        "hour_of_day": np.random.choice(range(8, 22), size=n_normal),  # Mostly business hours
        "day_of_week": np.random.choice(range(5), size=n_normal),  # Mostly weekdays
        "merchant_category": np.random.choice(range(10), size=n_normal, p=[0.2, 0.15, 0.15, 0.1, 0.1, 0.1, 0.08, 0.05, 0.04, 0.03]),
        "customer_age_days": np.random.exponential(scale=365, size=n_normal),
        "txn_frequency_1h": np.random.poisson(lam=1.5, size=n_normal),
        "avg_amount_30d": np.random.lognormal(mean=4.0, sigma=0.5, size=n_normal),
    }
    
    # Anomalous transactions (fraud patterns)
    anomaly_data = {
        "amount": np.random.lognormal(mean=7.0, sigma=2.0, size=n_anomaly),  # Higher amounts
        "hour_of_day": np.random.choice([0, 1, 2, 3, 4, 5, 23], size=n_anomaly),  # Odd hours
        "day_of_week": np.random.choice(range(7), size=n_anomaly),  # Any day
        "merchant_category": np.random.choice([7, 8, 9], size=n_anomaly),  # Rare categories
        "customer_age_days": np.random.uniform(low=0, high=30, size=n_anomaly),  # New accounts
        "txn_frequency_1h": np.random.poisson(lam=8, size=n_anomaly),  # High frequency
        "avg_amount_30d": np.random.lognormal(mean=3.0, sigma=0.3, size=n_anomaly),  # Different pattern
    }
    
    # Combine datasets
    normal_df = pd.DataFrame(normal_data)
    normal_df["is_fraud"] = 0
    
    anomaly_df = pd.DataFrame(anomaly_data)
    anomaly_df["is_fraud"] = 1
    
    combined = pd.concat([normal_df, anomaly_df], ignore_index=True)
    combined = combined.sample(frac=1, random_state=42).reset_index(drop=True)
    
    logger.info(f"Generated data shape: {combined.shape}")
    logger.info(f"Fraud ratio: {combined['is_fraud'].mean():.2%}")
    
    return combined


def train_model(data: pd.DataFrame) -> tuple:
    """
    Train an Isolation Forest model for anomaly detection.
    
    Returns:
        tuple: (trained_model, scaler, feature_columns)
    """
    logger.info("Training Isolation Forest model...")
    
    feature_columns = [
        "amount",
        "hour_of_day",
        "day_of_week",
        "merchant_category",
        "customer_age_days",
        "txn_frequency_1h",
        "avg_amount_30d",
    ]
    
    X = data[feature_columns].values
    
    # Scale features
    scaler = StandardScaler()
    X_scaled = scaler.fit_transform(X)
    
    # Train Isolation Forest
    model = IsolationForest(
        n_estimators=100,
        contamination=0.05,  # Expected proportion of outliers
        max_samples="auto",
        random_state=42,
        n_jobs=-1,
        verbose=1
    )
    
    model.fit(X_scaled)
    
    # Evaluate on training data
    predictions = model.predict(X_scaled)
    anomaly_scores = model.decision_function(X_scaled)
    
    # Convert predictions: -1 (anomaly) -> 1 (fraud), 1 (normal) -> 0 (not fraud)
    predicted_fraud = (predictions == -1).astype(int)
    
    if "is_fraud" in data.columns:
        actual_fraud = data["is_fraud"].values
        accuracy = (predicted_fraud == actual_fraud).mean()
        logger.info(f"Training accuracy: {accuracy:.2%}")
        
        # Calculate precision/recall
        true_positives = ((predicted_fraud == 1) & (actual_fraud == 1)).sum()
        false_positives = ((predicted_fraud == 1) & (actual_fraud == 0)).sum()
        false_negatives = ((predicted_fraud == 0) & (actual_fraud == 1)).sum()
        
        precision = true_positives / (true_positives + false_positives) if (true_positives + false_positives) > 0 else 0
        recall = true_positives / (true_positives + false_negatives) if (true_positives + false_negatives) > 0 else 0
        
        logger.info(f"Precision: {precision:.2%}")
        logger.info(f"Recall: {recall:.2%}")
    
    return model, scaler, feature_columns


def save_model(model, scaler, feature_columns, output_path: str = "model.pkl"):
    """
    Save the trained model, scaler, and feature columns to a pickle file.
    """
    model_bundle = {
        "model": model,
        "scaler": scaler,
        "feature_columns": feature_columns,
        "version": "1.0.0"
    }
    
    output_file = Path(output_path)
    with open(output_file, "wb") as f:
        pickle.dump(model_bundle, f)
    
    logger.info(f"Model saved to: {output_file.absolute()}")


def main():
    """Main training pipeline."""
    logger.info("=" * 60)
    logger.info("Sentinel Risk Engine - Model Training")
    logger.info("=" * 60)
    
    # Generate synthetic data
    data = generate_synthetic_data(n_samples=10000, anomaly_ratio=0.05)
    
    # Train model
    model, scaler, feature_columns = train_model(data)
    
    # Save model
    save_model(model, scaler, feature_columns)
    
    logger.info("=" * 60)
    logger.info("Training complete!")
    logger.info("=" * 60)


if __name__ == "__main__":
    main()
