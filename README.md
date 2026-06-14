# BedrockMQ

基于 MySQL 的轻量级分布式 Pub-Sub 消息队列，以 Spring Boot Starter 形式接入，零额外中间件依赖。

---

## 特性

- **零侵入** — 显式配置 `bedrock.mq.enabled=false` 时，所有 Bean 均不加载；引入 starter 后默认启用
- **Pub-Sub 扇出** — 一条消息广播给所有订阅该 topic 的 consumer，各自独立消费
- **分布式互斥** — 基于 MySQL `UPDATE ... WHERE status=0` CAS 原子抢占，无需 Redis
- **延迟 / 定时消息** — 通过 `scheduledAt` 字段支持延迟投递
- **自动重试** — 可按 consumer 独立配置重试次数，失败后自动重新入队
- **节点宕机恢复** — 超时任务自动将卡在 PROCESSING 的记录重置
- **管理后台** — 独立部署的 Web 管理界面，支持消息查看、重试、取消、发送

---

## 技术栈

| 依赖 | 版本 |
|------|------|
| Spring Boot | 2.7.18 |
| MySQL | 5.7+ |
| mysql-connector-j | 8.0.33 |
| Jackson | 随 Spring Boot BOM |

---

## 模块结构

| 模块 | 说明 |
|------|------|
| `bedrockmq-spring-boot-starter` | 核心库，引入即用 |
| `bedrockmq-admin` | 管理后台（Spring Boot Web App） |
| `bedrockmq-admin-frontend` | 管理后台前端静态资源 |
| `bedrockmq-example` | 接入示例 |
| `bedrockmq-docs` | 文档 |

---

## 快速接入

### 1. 添加依赖

```xml
<dependency>
    <groupId>top.redjujubetree</groupId>
    <artifactId>bedrockmq-spring-boot-starter</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

### 2. 初始化数据库

MySQL：执行 `bedrockmq-spring-boot-starter/src/main/resources/schema-mysql.sql`。  
SQLite：执行 `bedrockmq-spring-boot-starter/src/main/resources/schema-sqlite.sql`。

### 3. 关闭模块（可选）

引入 starter 后默认启用，不需要时显式禁用：

```properties
bedrock.mq.enabled=false
```

### 4. 编写消费者

```java
@BedrockConsumer(value = "order", topic = "order")
public class OrderProcessor implements MessageProcessor {
    @Override
    public void process(BedrockMessage message) throws Exception {
        // 抛出异常 → 触发重试；正常返回 → 标记 COMPLETED
        OrderDTO order = objectMapper.readValue(message.getPayload(), OrderDTO.class);
        // 业务逻辑...
    }
}

// 同一 topic，多个独立 consumer
@BedrockConsumer(value = "billing", topic = "order", maxRetry = 5)
public class BillingProcessor implements MessageProcessor { ... }
```

### 5. 发送消息

```java
@Autowired
MessageProducer producer;

// 立即发送
producer.send("order", "order-service", orderDTO);

// 延迟 10 分钟
producer.sendDelayed("order", "order-service", orderDTO, Duration.ofMinutes(10));

// 批量发送（单事务）
producer.sendBatch(List.of(
    new BedrockMessageRequest("order", "order-service", dto1),
    new BedrockMessageRequest("notify", "notify-service", dto2)
));
```

---

## 配置参数

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `bedrock.mq.enabled` | `true` | 模块总开关，引入 starter 后默认启用；设为 `false` 可禁用 |
| `bedrock.mq.node-id` | hostname + 随机串 | 集群节点唯一标识 |
| `bedrock.mq.batch-size` | `10` | 每次轮询拉取的消息数 |
| `bedrock.mq.poll-interval-ms` | `1000` | 轮询间隔（毫秒） |
| `bedrock.mq.processing-timeout-minutes` | `5` | 处理超时判定（分钟） |
| `bedrock.mq.default-concurrency` | `1` | 默认消费线程数 |
| `bedrock.mq.type-concurrency.{topic}:{consumer}` | — | 指定 (topic, consumer) 的消费线程数 |
| `bedrock.mq.db-dialect` | `auto` | 数据库方言：`mysql`、`sqlite`、`auto`（自动检测） |

---

## 消费状态机

```
PENDING(0) ──抢占成功──→ PROCESSING(1) ──业务成功──→ COMPLETED(2)
    ↑                          │
    │          业务失败，retry_count + 1 < max_retry
    └──────────────────────────┘
                               │
              业务失败，retry_count + 1 >= max_retry
                               ↓
                          FAILED(3)
```

---

## 管理后台

独立部署，默认地址：`http://localhost:8080/bedrockmq-admin`

配置：

```properties
spring.datasource.url=jdbc:mysql://localhost:3306/your_db?...
spring.datasource.username=your_user
spring.datasource.password=your_password

bedrock.mq.enabled=true
```

功能：消息列表与详情、手动发送、重试 / 取消、调整重试次数、订阅管理、统计概览。

---

## 数据库表

| 表 | 说明 |
|----|------|
| `bedrock_message` | 不可变消息日志，写入后不更新 |
| `bedrock_subscription` | 消费者订阅注册，启动时 UPSERT |
| `bedrock_consume_record` | 每条消息 × 每个 consumer 的消费状态 |

详见 [bedrockmq-docs/docs/database-schema.md](bedrockmq-docs/docs/database-schema.md)。

---

## License

[LICENSE](LICENSE)
