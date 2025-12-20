"""Debug script to test Kafka message parsing."""
import json
import asyncio
from aiokafka import AIOKafkaConsumer
from pydantic import BaseModel, Field

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


async def debug_consumer():
    consumer = AIOKafkaConsumer(
        "txn.created",
        bootstrap_servers="localhost:9092",
        group_id="debug-consumer",
        auto_offset_reset="earliest",
        value_deserializer=lambda m: json.loads(m.decode("utf-8"))
    )
    
    await consumer.start()
    print("Consumer started, waiting for messages...")
    
    try:
        async for message in consumer:
            print(f"\n{'='*60}")
            print(f"Partition: {message.partition}, Offset: {message.offset}")
            print(f"Raw value type: {type(message.value)}")
            print(f"Raw value: {message.value}")
            
            try:
                event = TransactionEvent(**message.value)
                print(f"✅ Parsed successfully!")
                print(f"   ID: {event.id}")
                print(f"   Amount: {event.amount}")
                print(f"   Status: {event.status}")
            except Exception as e:
                print(f"❌ Parse error: {e}")
            
            # Only process first 3 messages
            if message.offset >= 2:
                break
    finally:
        await consumer.stop()


if __name__ == "__main__":
    asyncio.run(debug_consumer())
