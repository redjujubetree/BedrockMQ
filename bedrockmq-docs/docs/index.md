# BedrockMQ Documentation

Lightweight message queue backed by an existing MySQL database. Eliminates the need for a dedicated broker (RabbitMQ, Kafka) for Spring Boot applications that already have MySQL. Supports pub-sub fan-out, delayed delivery, per-consumer retry, and distributed processing — all without Redis or any additional infrastructure.

## Contents

| Document | Description |
|----------|-------------|
| [Quick Start](quickstart.md) | Add the dependency, configure datasource, write a consumer, send a message |
| [API Reference](api-reference.md) | `MessageProducer` methods and `@BedrockConsumer` annotation |
| [Admin Module](admin.md) | Deploy the admin application, full REST API for managing consume records and subscriptions |
| [Database Schema](database-schema.md) | DDL, column descriptions, index rationale, CAS and timeout-recovery SQL |

## Architecture overview

```
Producer                       Consumer JVM
────────                       ────────────
send(topic, payload)           @BedrockConsumer("order")
  │                            registered in bedrock_subscription
  ├─ INSERT bedrock_message    on startup
  │
  ├─ SELECT bedrock_subscription WHERE topic=? AND status=1
  │
  └─ INSERT bedrock_consume_record  (one row per enabled subscriber)
                                        │
                                        ▼
                               PerTypePollingManager
                               polls per (topic, consumer)
                                        │
                                        ▼
                               CAS acquire → process() → COMPLETED
                                                  └──────→ retry / FAILED
```

Key properties:
- `bedrock_message` rows are **immutable** after insert.
- Each subscriber gets its own `bedrock_consume_record` and processes independently.
- Distributed mutex via a single atomic `UPDATE … WHERE status=0` — no Redis required.
- Subscriptions persist in the DB; admin-disabled subscriptions survive restarts.
