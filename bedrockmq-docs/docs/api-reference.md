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
| payload | Required; String passed through as-is, any other non-null object is serialized to JSON |
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
| payload | Object | required | Business data; must not be null |
| maxRetry | int | 0 | `0` = use subscription default |
| scheduledAt | LocalDateTime | NOW() | Earliest eligible processing time |

---

## HTTP client (`BedrockHttpClient`)

The Starter provides both `BedrockHttpClient` and an optional `POST /bedrock/v1/messages` endpoint. Set `bedrock.mq.http.client.enabled=true` to register the client independently of the server and database infrastructure. In a Spring MVC application with the Starter, keep `bedrock.mq.enabled=true` (the default) and set `bedrock.mq.http.server.enabled=true` to register the endpoint. The Starter does not start a web server; the host application must provide Spring MVC. Configure outbound clients independently. Each has its own address, port, key, and algorithm. Supported algorithms are `AES-256-GCM` (32-byte key, default) and `AES-128-GCM` (16-byte key); keys are Base64 encoded.

```yaml
bedrock:
  mq:
    http:
      client:
        enabled: true
      clients:
        primary:
          address: https://mq-1.example.com
          port: 9527
          path: /bedrock/v1/messages
          encryption-key: ${PRIMARY_MQ_KEY}
          algorithm: AES-256-GCM
        backup:
          address: https://mq-2.example.com
          port: 9528
          encryption-key: ${BACKUP_MQ_KEY}
          algorithm: AES-128-GCM
```

```java
@Autowired BedrockHttpClient client;
Long id = client.send("primary", "order", "checkout-service", orderPayload);
// Or client.send("backup", new BedrockMessageRequest(topic, source, payload));
```

For a sender that uses only this HTTP client, set `bedrock.mq.enabled=false` while keeping `bedrock.mq.http.client.enabled=true`. If it has no database configuration, also exclude Spring Boot's `DataSourceAutoConfiguration`, which is brought in by the Starter's JDBC dependency. The HTTP client bean itself requires neither a datasource nor the HTTP server.

On the receiving Spring MVC application, configure one shared inbound key and algorithm. Individual client entries are optional and override the shared values when present:

```yaml
bedrock:
  mq:
    http:
      server:
        enabled: true
        encryption-key: ${PRIMARY_MQ_KEY}
        algorithm: AES-256-GCM
        clients:
          backup:
            encryption-key: ${BACKUP_MQ_KEY}
            algorithm: AES-128-GCM
```

The first argument selects a named outbound client; each call goes only to that client's address. It does not automatically retry a failed request because a timeout can occur after the server commits; the caller must handle uncertain outcomes and any retry duplicates. It returns the message ID, throws `IOException` for transport or HTTP errors, and supports `maxRetry` and `scheduledAt` through `BedrockMessageRequest`. A String payload is sent as-is; a blank String is rejected before sending. Other objects are serialized to JSON. Each request uses a fresh random IV. The request's plaintext `client` name selects an optional `bedrock.mq.http.server.clients.<name>` override; otherwise the controller uses the shared `server.encryption-key` and `server.algorithm`. A client override may omit either field to inherit its shared value. By default, the endpoint returns a numeric ID, for example `{ "id": 42 }`, and rejects invalid ciphertext with HTTP 400. Without a shared key, only explicitly configured client names are accepted. Use HTTPS and restrict access to the endpoint; message encryption alone does not prevent replay or protect response traffic.

## Consumer Annotation (`@BedrockConsumer`)

Consumer beans must implement `top.redjujubetree.bedrock.mq.consumer.BedrockMessageConsumer`.

```java
@BedrockConsumer(
    value    = "consumer-name",   // required; consumer identity, must be unique per topic
    topic    = "topic-name",      // required; must not be empty
    maxRetry = 3                  // optional; stored in bedrock_subscription
)
public class MyConsumer implements BedrockMessageConsumer {
    @Override
    public void consume(BedrockMessage message) throws Exception {
        // throw → retry; return normally → COMPLETED
    }
}
```

Both `value` and `topic` are required. `BedrockConsumerRegistry` throws `IllegalStateException` at startup if either is blank or if the same `(topic, consumer)` pair is registered more than once.

### Runtime registration

Applications can inject `BedrockConsumerRegistry` and register a handler after startup without an annotation:

```java
consumerRegistry.registerConsumer("orders", "external-app", 5, message -> {
    // Process the message; throw to retry.
});
```

`registerConsumer(String topic, String consumerName, int maxRetry, BedrockMessageConsumer consumer)` inserts a subscription if absent and starts polling immediately. `maxRetry` must be positive and applies only to a new subscription; an existing row keeps its `status` and `max_retry`. A duplicate local `(topic, consumer)` registration throws `IllegalStateException`. Blank names, a null handler, or a nonpositive retry count throw `IllegalArgumentException`. Register before publishing if new messages should fan out to this consumer; messages published before the subscription exists do not gain consume records retroactively.

`BedrockMessage` fields available inside `consume()`:

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

### ID generation extension

Implement `top.redjujubetree.bedrock.mq.id.BedrockIdGenerator` and expose exactly one Spring bean to use application-generated IDs. Its `Number nextId(Object entity)` method receives a `BedrockMessage`, `BedrockSubscription`, or `BedrockConsumeRecord` before insertion. The entity contains the values prepared for the insert; optional fields may be null. Without this bean, the database generates IDs as before. The starter converts the returned number to a Java `long`; the generator is responsible for returning a positive whole number that fits in a signed `long` and is unique across every writer sharing the database. Duplicate IDs fail at the database constraint.

All properties are under the `bedrock.mq.*` prefix in `application.properties`.

| Property | Default | Description |
|----------|---------|-------------|
| `bedrock.mq.enabled` | `true` | Enables core database and consumer infrastructure by default; the independently enabled HTTP client remains available when set to `false` |
| `bedrock.mq.node-id` | hostname + random suffix | Unique node identifier used for the CAS acquire lock |
| `bedrock.mq.batch-size` | `10` | Maximum records fetched per poll per pair; actual limit is capped by available worker capacity |
| `bedrock.mq.poll-interval-ms` | `1000` | Poll interval in milliseconds |
| `bedrock.mq.processing-timeout-minutes` | `15` | Minutes before a PROCESSING record is considered stuck and reset |
| `bedrock.mq.default-concurrency` | `1` | Worker threads per `(topic, consumer)` pair |
| `bedrock.mq.type-concurrency.<topic>:<consumer>` | — | Override concurrency for a specific pair, e.g. `bedrock.mq.type-concurrency.order:order=3` |
| `bedrock.mq.db-dialect` | `auto` | SQL dialect: `mysql`, `sqlite`, or `auto` (detects from JDBC metadata) |
| `bedrock.mq.http.server.enabled` | `false` | Register the encrypted HTTP endpoint in a Spring MVC host when `bedrock.mq.enabled=true` |
| `bedrock.mq.http.client.enabled` | `false` | Register the outbound HTTP client independently of the server and `bedrock.mq.enabled` |
| `bedrock.mq.http.server.encryption-key` | — | Shared inbound Base64 AES key |
| `bedrock.mq.http.server.algorithm` | `AES-256-GCM` | Shared inbound algorithm |
| `bedrock.mq.http.server.clients.<name>.encryption-key` | shared key | Optional per-client inbound key override |
| `bedrock.mq.http.server.clients.<name>.algorithm` | shared algorithm | Optional per-client algorithm override |
| `bedrock.mq.http.clients.<name>.address` | — | Client HTTP(S) origin, such as `https://mq.example.com` |
| `bedrock.mq.http.clients.<name>.port` | — | Client destination port (1–65535) |
| `bedrock.mq.http.clients.<name>.path` | `/bedrock/v1/messages` | Client endpoint path, including any host context path |
| `bedrock.mq.http.clients.<name>.encryption-key` | — | Per-client Base64 AES key; configure same key on receiver |
| `bedrock.mq.http.clients.<name>.algorithm` | `AES-256-GCM` | `AES-256-GCM` or `AES-128-GCM`; configure same algorithm on receiver |
| `bedrock.mq.http.connect-timeout-ms` | `3000` | HTTP connection timeout |
| `bedrock.mq.http.read-timeout-ms` | `5000` | HTTP response timeout |

---

## Admin REST API

See [Admin Module](admin.md) for setup instructions and the full REST API reference.
