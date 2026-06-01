# Admin Module

`bedrockmq-admin` is a standalone Spring Boot application for monitoring and operating a BedrockMQ deployment. It exposes a REST API to inspect consume records, retry failures, manage subscriptions, and send messages for testing.

## Setup

### 1. Configure application.properties

```properties
# Database — same datasource as the target deployment
spring.datasource.url=jdbc:mysql://localhost:3306/your_db?useUnicode=true&characterEncoding=utf-8&useSSL=false&serverTimezone=GMT%2B8
spring.datasource.username=root
spring.datasource.password=yourpassword

# BedrockMQ is enabled by default after importing the starter.
# Explicitly disable only if you want to turn it off.
# bedrock.mq.enabled=false
```

### 2. Start the application

```bash
java -jar bedrockmq-admin.jar
```

The admin module has no consumer processors of its own. `ProcessorRegistry` will register zero handlers on startup — polling threads are not created and no messages are consumed by the admin JVM.

---

## REST API

Base path: `/bedrock`

### Consume Records

#### List records

```
GET /bedrock/messages
```

Query parameters:

| Param | Type | Default | Description |
|-------|------|---------|-------------|
| topic | String | — | Filter by topic |
| consumer | String | — | Filter by consumer |
| status | Integer | — | `0`=PENDING `1`=PROCESSING `2`=COMPLETED `3`=FAILED |
| page | long | 1 | Page number (1-based) |
| size | long | 20 | Page size |

Response: paginated `PageResult` — fields `records`, `total`, `current`, `size`. The list rows include `messageSource` but do **not** include `payload`; use the detail endpoint to retrieve `payload`.

#### Get record

```
GET /bedrock/messages/{id}
```

Returns a single consume record including `payload` and `messageSource` (fetched via JOIN with `bedrock_message`).

#### Send message

```
POST /bedrock/messages
Content-Type: application/json

{
  "topic":         "order",
  "messageSource": "admin",
  "payload":       "{\"orderId\":1}",
  "maxRetry":      0,
  "scheduledAt":   "2099-01-01T10:00:00"
}
```

`topic`, `messageSource`, and `payload` are required. `scheduledAt` is ISO-8601 (`yyyy-MM-ddTHH:mm:ss`); omit for immediate delivery. `maxRetry=0` defers to each subscription's configured default.

Response:

```json
{ "id": 42 }
```

#### Retry failed record

```
POST /bedrock/messages/{id}/retry
```

Resets a FAILED consume record to PENDING and clears `retry_count`, `error_msg`, and `node_id`. Returns `404` if the record does not exist or is not in FAILED status.

#### Cancel pending record

```
POST /bedrock/messages/{id}/cancel
```

Moves a PENDING consume record to FAILED (status 3). Returns `404` if the record is not in PENDING status.

#### Update max retry

```
POST /bedrock/messages/{id}/max-retry
Content-Type: application/json

{ "maxRetry": 5 }
```

Updates `max_retry` on a single consume record. `maxRetry` must be `>= 0`. Returns `404` if the record does not exist.

#### Batch update max retry

```
POST /bedrock/messages/batch/max-retry
Content-Type: application/json

{ "ids": [1, 2, 3], "maxRetry": 5 }
```

Updates `max_retry` on multiple consume records in a single statement. Returns `{ "updated": N }` with the number of affected rows.

#### Delete record

```
POST /bedrock/messages/{id}/delete
```

Soft-deletes the consume record by setting `deleted=1`. The row is retained in the database for audit purposes but is excluded from all list queries, detail lookups, and polling. The parent `bedrock_message` row is unaffected.

---

### Subscriptions

#### List subscriptions

```
GET /bedrock/subscriptions
```

Returns all rows in `bedrock_subscription` ordered by id.

#### Enable subscription

```
POST /bedrock/subscriptions/{id}/enable
```

Sets `status=1`. Subsequent messages on this topic will create a consume record for this consumer.

#### Disable subscription

```
POST /bedrock/subscriptions/{id}/disable
```

Sets `status=0`. Subsequent messages will **not** create consume records for this consumer.

> **Note:** disabling a subscription only affects future fanout. Consume records already in `bedrock_consume_record` are unaffected and will continue to be polled and processed by any running consumer JVM.

---

### Stats & Monitoring

#### Stats

```
GET /bedrock/stats
```

Returns consume record counts grouped by `(topic, consumer, status)`.

Example response:

```json
[
  { "topic": "order",  "consumer": "order",   "status": 0, "count": "12" },
  { "topic": "order",  "consumer": "billing",  "status": 2, "count": "304" },
  { "topic": "notify", "consumer": "notify",   "status": 3, "count": "1" }
]
```

#### Registered processors

```
GET /bedrock/processors
```

Returns the `topic:consumer` keys for all enabled subscriptions in `bedrock_subscription` (status=1). This reflects the DB state, not an in-memory registry — the list is populated regardless of whether a consumer JVM is running.
