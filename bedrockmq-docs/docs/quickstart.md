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
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

## 3. Configure application.properties

If your project already has a `spring.datasource` configured, **no additional configuration is needed** — BedrockMQ is enabled by default once the starter is imported.

To disable it explicitly:

```properties
bedrock.mq.enabled=false
```

See [API Reference — Configuration](api-reference.md#configuration) for all optional tuning properties.

## 4. Write a consumer

`@BedrockConsumer` is meta-annotated with `@Component`, so it registers the class as a Spring bean automatically — no separate `@Component` or `@Service` needed.

```java
import top.redjujubetree.bedrock.mq.annotation.BedrockConsumer;
import top.redjujubetree.bedrock.mq.processor.MessageProcessor;
import top.redjujubetree.bedrock.mq.entity.BedrockMessage;

@BedrockConsumer(value = "order", topic = "order")
public class OrderProcessor implements MessageProcessor {
    @Override
    public void process(BedrockMessage message) throws Exception {
        OrderDTO dto = objectMapper.readValue(message.getPayload(), OrderDTO.class);
        // business logic — throw to trigger retry, return normally to mark COMPLETED
    }
}
```

For pub-sub fan-out (multiple consumers on the same topic):

```java
@BedrockConsumer(value = "billing", topic = "order", maxRetry = 5)
public class BillingProcessor implements MessageProcessor { ... }
```

On startup `ProcessorRegistry` registers both handlers into `bedrock_subscription` (insert-if-not-exists) and starts independent polling threads for each. If a row already exists, the DB values for `max_retry` and `status` are kept as-is; `@BedrockConsumer(maxRetry=N)` only takes effect on first insert.

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
producer.sendBatch(List.of(
    new BedrockMessageRequest("order",  "checkout-service", payload1),
    new BedrockMessageRequest("notify", "checkout-service", payload2)
));
```

The producer queries `bedrock_subscription` at send time and creates one `bedrock_consume_record` per enabled subscriber. It has no dependency on any consumer JVM being alive.
