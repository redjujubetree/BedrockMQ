# API Reference

## Producer API (`MessageProducer`)

All methods are transactional. The producer queries `bedrock_subscription` at send time and creates one `bedrock_consume_record` per enabled subscriber — no consumer JVM needs to be running.

### send

```java
Long send(String topic, String messageSource, Object payload)
Long send(String topic, String messageSource, Object payload, int maxRetry)
```

Inserts a message and fans out consume records immediately.

| Parameter | Description |
|-----------|-------------|
| topic | Message topic; routes to all enabled subscribers |
| messageSource | Sending service name; required — throws `IllegalArgumentException` if null or empty |
| payload | String passed through as-is; any other object is serialized to JSON |
| maxRetry | `0` (default) = defer to each subscriber's `bedrock_subscription.max_retry` |

Returns the generated `bedrock_message.id`.

### sendDelayed

```java
Long sendDelayed(String topic, String messageSource, Object payload, Duration delay)
```

Sets `scheduled_at = NOW() + delay` on all consume records. Uses `maxRetry=0` (subscription default).

### sendAt

```java
Long sendAt(String topic, String messageSource, Object payload, int maxRetry, LocalDateTime scheduledAt)
```

Full control over scheduled time and retry count.

### sendBatch

```java
void sendBatch(List<BedrockMessageRequest> requests)
```

Inserts all messages and their consume records in a single transaction.

`BedrockMessageRequest` fields:

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| topic | String | required | Message topic |
| messageSource | String | required | Sending service name |
| payload | Object | required | Business data |
| maxRetry | int | 0 | `0` = use subscription default |
| scheduledAt | LocalDateTime | NOW() | Earliest eligible processing time |

---

## Consumer Annotation (`@BedrockConsumer`)

```java
@BedrockConsumer(
    value    = "consumer-name",   // required; consumer identity, must be unique per topic
    topic    = "topic-name",      // required; must not be empty
    maxRetry = 3                  // optional; stored in bedrock_subscription
)
public class MyProcessor implements MessageProcessor {
    @Override
    public void process(BedrockMessage message) throws Exception {
        // throw → retry; return normally → COMPLETED
    }
}
```

Both `value` and `topic` are required. `ProcessorRegistry` throws `IllegalStateException` at startup if either is blank.

`BedrockMessage` fields available inside `process()`:

| Field | Source |
|-------|--------|
| id | `bedrock_message.id` |
| topic | `bedrock_message.topic` |
| messageSource | `bedrock_message.message_source` |
| payload | `bedrock_message.payload` (raw JSON string) |
| createdAt | `bedrock_message.created_at` |
| updatedAt | `bedrock_message.updated_at` |

---

## Configuration

All properties are under the `bedrock.mq.*` prefix in `application.properties`.

| Property | Default | Description |
|----------|---------|-------------|
| `bedrock.mq.enabled` | `true` | Module master switch. Enabled by default when the starter is imported; set to `false` to disable |
| `bedrock.mq.node-id` | hostname + random suffix | Unique node identifier used for the CAS acquire lock |
| `bedrock.mq.batch-size` | `10` | Records fetched per poll per `(topic, consumer)` pair |
| `bedrock.mq.poll-interval-ms` | `1000` | Poll interval in milliseconds |
| `bedrock.mq.processing-timeout-minutes` | `15` | Minutes before a PROCESSING record is considered stuck and reset |
| `bedrock.mq.default-concurrency` | `1` | Worker threads per `(topic, consumer)` pair |
| `bedrock.mq.type-concurrency.<topic>:<consumer>` | — | Override concurrency for a specific pair, e.g. `bedrock.mq.type-concurrency.order:order=3` |
| `bedrock.mq.db-dialect` | `auto` | SQL dialect: `mysql`, `sqlite`, or `auto` (detects from JDBC metadata) |

---

## Admin REST API

See [Admin Module](admin.md) for setup instructions and the full REST API reference.
