# Quick Start

## Prerequisites

- Java 8+
- Spring Boot 2.7.x

## 1. Initialize the database

Run the DDL against your database instance. This creates three tables: `bedrock_message`, `bedrock_subscription`, and `bedrock_consume_record`.

- **MySQL**: `bedrockmq-spring-boot-starter/src/main/resources/schema-mysql.sql`
- **SQLite**: `bedrockmq-spring-boot-starter/src/main/resources/schema-sqlite.sql`

## 2. Add the dependency

```xml
<dependency>
    <groupId>top.redjujubetree</groupId>
    <artifactId>bedrockmq-spring-boot-starter</artifactId>
    <version>0.1.0</version>
</dependency>
```

For SQLite, add the JDBC driver to the host application (the starter does not include database drivers):

```xml
<dependency>
    <groupId>org.xerial</groupId>
    <artifactId>sqlite-jdbc</artifactId>
    <version>3.45.3.0</version>
</dependency>
```

For MySQL, ensure the host application has a MySQL JDBC driver. These versions match the current repository POM; check Maven Central when upgrading.

## 3. Configure application.properties

If your project already has a `spring.datasource` configured, **no additional configuration is needed** — BedrockMQ is enabled by default once the starter is imported.

For a new SQLite datasource, for example:

```properties
spring.datasource.url=jdbc:sqlite:/path/to/bedrockmq.db
spring.datasource.driver-class-name=org.sqlite.JDBC
```

To disable the core database and consumer infrastructure, set the following property. The separately enabled HTTP client remains available:

```properties
bedrock.mq.enabled=false
```

See [API Reference — Configuration](api-reference.md#configuration) for all optional tuning properties.

When upgrading an existing database from a version without fixed processing deadlines, run the matching migration once before deployment:

- MySQL: `migration-processing-expires-mysql.sql`
- SQLite: `migration-processing-expires-sqlite.sql`

## 4. Write a consumer

`@BedrockConsumer` is meta-annotated with `@Component`, so it registers the class as a Spring bean automatically — no separate `@Component` or `@Service` needed.

```java
import top.redjujubetree.bedrock.mq.annotation.BedrockConsumer;
import top.redjujubetree.bedrock.mq.consumer.BedrockMessageConsumer;
import top.redjujubetree.bedrock.mq.entity.BedrockMessage;

@BedrockConsumer(value = "order", topic = "order")
public class OrderConsumer implements BedrockMessageConsumer {
    @Override
    public void consume(BedrockMessage message) throws Exception {
        OrderDTO dto = objectMapper.readValue(message.getPayload(), OrderDTO.class);
        // business logic — throw to trigger retry, return normally to mark COMPLETED
    }
}
```

For pub-sub fan-out (multiple consumers on the same topic):

```java
@BedrockConsumer(value = "billing", topic = "order", maxRetry = 5)
public class BillingConsumer implements BedrockMessageConsumer { ... }
```

On startup `BedrockConsumerRegistry` registers both handlers into `bedrock_subscription` (insert-if-not-exists). All registered pairs share one multi-thread polling scheduler, while each pair keeps its independently sized worker pool. If a row already exists, the DB values for `max_retry` and `status` are kept as-is; `@BedrockConsumer(maxRetry=N)` only takes effect on first insert.

For handlers supplied by another application at runtime, inject `BedrockConsumerRegistry` and call `registerConsumer("order", "external-app", 5, message -> { /* handle */ });`. This creates the subscription if needed and starts polling immediately, including when there were no annotated consumers at startup. Register before publishing messages that should reach this consumer; existing messages are not backfilled. Existing subscription settings remain in the database.

## 5. Send a message

```java
@Autowired MessageProducer producer;

// Immediate delivery
producer.send("order", "checkout-service", orderPayload);

// Delayed delivery
producer.sendDelayed("order", "checkout-service", orderPayload, Duration.ofMinutes(10));

// Scheduled delivery
producer.sendAt("order", "checkout-service", orderPayload, 3, LocalDateTime.of(2099, 1, 1, 0, 0));

// Batch (single transaction)
producer.sendBatch(Arrays.asList(
    new BedrockMessageRequest("order",  "checkout-service", payload1),
    new BedrockMessageRequest("notify", "checkout-service", payload2)
));
```

The producer queries `bedrock_subscription` at send time and creates one `bedrock_consume_record` per enabled subscriber. It has no dependency on any consumer JVM being alive.

To send through HTTP, set `bedrock.mq.http.client.enabled=true` and configure one or more named `bedrock.mq.http.clients` with separate address, port, key, and algorithm. This client works even when `bedrock.mq.http.server.enabled=false` or `bedrock.mq.enabled=false`. In the receiving Spring MVC application, keep `bedrock.mq.enabled=true` (the default), set `bedrock.mq.http.server.enabled=true`, and configure a shared `server.encryption-key` and `server.algorithm`; optional `server.clients.<name>` entries override either value for individual clients. The Starter registers the encrypted endpoint there. Inject `BedrockHttpClient` and call `client.send("primary", "order", "checkout-service", orderPayload)`. A failed request is returned to the caller without an automatic retry. See the [HTTP client reference](api-reference.md#http-client-bedrockhttpclient) for YAML and security details.

## Optional custom IDs

By default, all three tables use database-generated IDs. To supply IDs in the application, register one `BedrockIdGenerator` bean:

```java
@Bean
public BedrockIdGenerator bedrockIdGenerator() {
    AtomicLong sequence = new AtomicLong(1000L); // example only; use a distributed generator in a cluster
    return entity -> sequence.incrementAndGet();
}
```

The `entity` argument is a `BedrockMessage`, `BedrockSubscription`, or `BedrockConsumeRecord` containing the values prepared for insertion; optional fields may be null. IDs must be positive, unique across all writers for each table, and fit in a signed Java `long`. The starter then supplies IDs explicitly; no schema change is required for MySQL or SQLite. All writers sharing a database must use a compatible strategy. Before switching an existing database, ensure new IDs cannot collide with historical IDs, and avoid overlapping ID ranges during rolling deployment. The example counter is suitable only for a single process and does not survive restarts.

Delivery is at least once. A handler may still be running when its fixed processing deadline expires and another attempt begins, so handlers must be idempotent.
