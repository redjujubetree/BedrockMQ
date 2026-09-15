# BedrockMQ 开发指南

本文件是 Codex/代码代理在本仓库中的首要项目级指令。目标是维护一个基于数据库的轻量级分布式 Pub-Sub 系统，并确保 Spring Boot Starter 对宿主应用保持低侵入。

详细用户文档位于 `README.md` 与 `bedrockmq-docs/docs/`。本文件只保留开发时必须遵守的约束、架构事实和验证方式；不要在这里复制完整 API 文档。

## 工作原则

- 修改前先检查 `git status --short`，保留用户已有改动，不覆盖无关文件。
- 优先做范围最小、行为可验证的修改；不要借当前任务顺带重构其他模块。
- Java 代码必须兼容 Java 8，禁止在生产代码和文档示例中使用 `List.of`、`Map.of`、`var`、record 等高版本语法。
- 数据库逻辑必须同时考虑 MySQL 5.7+ 与 SQLite；新增 SQL 时验证两种数据库行为。
- 修改公共 API、配置项、数据库字段或状态机时，同步更新 README、正式文档、DDL、升级脚本和测试。
- BedrockMQ 提供至少一次投递语义。不要把固定处理期限、CAS 或事务描述成“恰好一次”；业务处理器仍需幂等。
- 未经用户明确要求，不提交、推送、重置 Git 历史或执行破坏性操作。

## 技术基线

| 项目 | 约束 |
|---|---|
| Java | 8 |
| Spring Boot | 2.7.18 |
| 数据访问 | Spring JDBC，`NamedParameterJdbcTemplate` |
| MySQL | 5.7+，Connector/J 8.0.33 |
| SQLite | `sqlite-jdbc`，通过 `SqlDialect` 或 metadata 自动识别 |
| JSON | starter 直接依赖 `jackson-databind` |
| 构建 | Maven 多模块 Reactor |

## 模块边界

| 模块 | 职责 |
|---|---|
| `bedrockmq-spring-boot-starter` | 核心自动配置、生产、消费、固定处理期限、恢复和 Mapper |
| `bedrockmq-admin` | 独立 Spring Boot 管理应用 |
| `bedrockmq-admin-frontend` | Vue 管理前端，构建产物作为静态资源打入 Admin |
| `bedrockmq-example` | Java 8 接入示例 |
| `bedrockmq-docs` | 正式设计、API、Admin 和数据库文档 |

根 `pom.xml` 是 Reactor Parent：

```text
groupId    = top.redjujubetree
artifactId = bedrockmq-parent
```

## 常用验证命令

按修改范围从小到大运行：

```bash
# starter 单元测试和处理期限数据库测试
mvn -pl bedrockmq-spring-boot-starter test

# Admin、前端构建及其依赖模块
mvn -pl bedrockmq-admin -am test

# 全仓验证
mvn test

# 检查空白错误
git diff --check
```

涉及数据库状态机、DDL、自动配置或公共 API 时，最终至少运行 `mvn test`。测试日志中故意注入的异常不代表失败，以 Maven 最终结果为准。

## 自动配置总开关

`BedrockAutoConfiguration` 是基础设施 Bean 的唯一注册入口：

```java
@Configuration
@ConditionalOnProperty(
    prefix = "bedrock.mq",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true
)
@EnableConfigurationProperties(BedrockMqProperties.class)
```

必须保持以下行为：

- 引入 starter 后默认启用。
- 显式设置 `bedrock.mq.enabled=false` 时，模块内所有基础设施 Bean 均不加载。
- `MessageProducer`、`BedrockMessageProcessor`、`BedrockConsumerRegistry`、`PerTypePollingManager`、`TimeoutRecoveryTask` 和 Mapper 不加独立 `@Component`，统一由自动配置创建。
- `@EnableConfigurationProperties` 继续受总开关控制。
- 不要在宿主应用中隐式启用 `@Scheduled`、组件扫描或额外全局配置。

## 数据模型与不可变约束

BedrockMQ 使用三张表：

- `bedrock_message`：不可变消息日志，写入后不更新。
- `bedrock_subscription`：持久化订阅注册。
- `bedrock_consume_record`：每条消息 × 每个 consumer 的独立消费状态。

核心约束：

- Producer 发布时查询启用的订阅，并同步创建独立消费记录，实现 Pub-Sub 扇出。
- Producer 不依赖 `BedrockConsumerRegistry`，生产者与消费者 JVM 可以完全分离。
- 停用订阅只阻止新消息扇出；已有消费记录仍可继续消费。
- `created_at`、`updated_at` 由 Java 显式赋值，避免依赖不同数据库的默认时间行为。
- `bedrock_message` 删除、更新或状态字段不得与消费状态混合。

完整 DDL 以以下文件为准：

- `bedrockmq-spring-boot-starter/src/main/resources/schema-mysql.sql`
- `bedrockmq-spring-boot-starter/src/main/resources/schema-sqlite.sql`
- `bedrockmq-docs/docs/database-schema.md`

## 消费状态机

```text
PENDING(0) --CAS + deadline--> PROCESSING(1) --success--> COMPLETED(2)
    ^                           |
    |                           +-- failure and retries remain --> PENDING
    |                           |
    +------ fixed timeout ------+
                                |
                                +-- retries exhausted --> FAILED(3)
```

未注册处理器的 `(topic, consumer)` 不参与轮询，记录必须保持 PENDING。

### 重试语义

- `max_retry` 表示最大执行次数，包含首次执行。
- 失败后使用 `nextRetry = retry_count + 1`。
- `nextRetry >= max_retry` 时进入 FAILED，否则回到 PENDING。
- Producer API 中 `maxRetry=0` 表示使用每个订阅自己的 `max_retry`。
- 管理端手动重试将 FAILED 重置为 PENDING，并清空重试计数、错误和执行所有权字段。

## CAS、固定处理期限与长任务

消费互斥不能只依赖 `node_id`。每次执行必须生成唯一 `processing_token`，并同时写入：

- `processing_started_at`
- `processing_expires_at`

抢占必须满足：

```sql
WHERE id = :id AND status = 0 AND deleted = 0
```

### 不可破坏的执行所有权规则

- 抢占时将 `processing_expires_at` 固定为开始时间加 `processing-timeout-minutes`，禁止续期或发送心跳。
- 成功与失败更新必须匹配 `status=PROCESSING` 和同一 `processing_token`。
- 条件更新影响 0 行表示执行所有权已丢失；记录警告并忽略迟到结果，禁止退化为无条件更新。
- token 属于内部所有权凭据，不通过 JSON 返回，也不写入普通日志。
- 恢复调度必须捕获异常，避免 `scheduleWithFixedDelay` 因一次异常永久停止。
- 到达固定处理期限后，恢复任务可将记录重置；仍在执行的旧 handler 可能与新尝试重叠，因此业务处理器必须幂等。
- 应用关闭时先停止轮询，再等待 worker。

默认配置：

| 配置 | 默认值 | 说明 |
|---|---:|---|
| `bedrock.mq.processing-timeout-minutes` | 15 | 抢占后单次执行的固定最长时间，期限不续期 |

恢复任务仅处理 `processing_expires_at <= now` 的 PROCESSING 记录。无期限的旧记录不自动恢复。

恢复时递增 `retry_count`，按 `max_retry` 决定 PENDING/FAILED，并清空所有执行所有权字段。已有数据库部署新代码前必须先执行一次：

- `migration-processing-expires-mysql.sql`
- `migration-processing-expires-sqlite.sql`

## 轮询与线程模型

当前轮询线程模型：

- 所有 `(topic, consumer)` 共享 1 个包含 2 个线程的 `ScheduledExecutorService`；
- 每个 `(topic, consumer)` 拥有 N 个 worker 线程，N 取 `type-concurrency`，未配置时取 `default-concurrency`。

调度使用 `scheduleWithFixedDelay`。关闭时先停止共享轮询调度器，再等待各 worker pool graceful shutdown。

worker 队列容量等于 `batch-size`，并使用 `AbortPolicy`。每次轮询先计算空闲 worker 与队列剩余容量；容量为 0 时跳过该 consumer，查询 limit 不超过当前可用容量。若容量检查后仍因竞争或关闭发生拒绝，捕获 `RejectedExecutionException` 并停止本轮提交；任务尚未执行 CAS，因此对应记录保持 PENDING，后续可再次拉取。禁止在共享调度线程中执行 handler。

## 处理器注册

`@BedrockConsumer` 同时是 Spring stereotype，消费者必须实现 `BedrockMessageConsumer`。

`BedrockConsumerRegistry`：

1. 使用 `ApplicationContext.getBeansWithAnnotation()` 查找 Bean。
2. 使用以下方式兼容 CGLIB/JDK 代理：

```java
AnnotationUtils.findAnnotation(
    AopUtils.getTargetClass(bean),
    BedrockConsumer.class
)
```

3. 建立 `topic:consumer -> consumer` 映射。
4. 对新订阅执行 insert-if-not-exists；已有 DB 行的 `status` 和 `max_retry` 必须保留。

同一个 `(topic, consumer)` 应只有一个处理器。修改注册逻辑时应检测重复并在启动时明确失败，不能静默覆盖。

## Mapper 与实体规则

- Mapper 是具体类，不是 MyBatis 接口。
- SQL 使用命名参数，禁止拼接用户输入。
- CAS、完成、失败和恢复必须检查受影响行数。
- 新增字段时同时更新实体、所有显式 SELECT 列、两份 DDL、升级脚本和数据库文档。
- `BedrockConsumeRecord.payload`、`messageSource`、`messageCreatedAt`、`messageUpdatedAt` 是 JOIN 字段，不属于 `bedrock_consume_record`。
- `selectPending`、`selectByIdWithMessage` 填充 payload 与 messageSource。
- `listMessages` 只包含 messageSource，不返回 payload。
- `selectById` 不 JOIN，消息内容字段为 null。
- `processingToken` 必须保持 `@JsonIgnore`。

## 异常处理

- 处理器正常返回表示成功；抛出异常触发重试。
- `error_msg` 数据库长度为 512 字符，写入前最多保留 500 字符并追加 `...`。
- `Exception.getMessage()` 为空时回退到堆栈文本。
- 调度器捕获异常后必须记录并允许下一周期继续，不能让异常逃出周期任务。
- 不要记录 payload、processing token、密码或其他敏感数据。

## Admin 约束

- Controller 的 `@RequestBody` 必须使用专用 DTO，禁止使用 `Map<String, Object>`。
- `@RequestParam`、`@PathVariable` 使用明确的基本包装类型。
- Long ID 通过 Jackson customizer 序列化为字符串，避免 JavaScript 精度丢失。
- Admin 当前没有内置鉴权，这是已知安全缺口；不要在文档中暗示可以安全暴露公网。
- 修改 Admin 写接口时必须考虑认证、输入验证、状态条件和审计影响。

## 测试要求

涉及消费状态或固定处理期限时至少覆盖：

- CAS 成功和失败；
- 正确 token 可以完成和失败；
- 错误或过期 token 影响 0 行；
- A 超时、B 重新抢占后，A 的迟到结果不能覆盖 B；
- 固定期限前不会恢复，到达期限时会恢复；
- 处理期限不会通过心跳或其他更新延长；
- 无处理期限的 PROCESSING 记录不会被恢复；
- MySQL 模式/H2 与真实 SQLite 行为；
- `bedrock.mq.enabled=false` 时基础设施 Bean 不加载。

涉及 Producer 时至少覆盖事务扇出、无订阅、订阅默认重试次数、覆盖重试次数、延迟发送、批量发送和序列化失败。

## 文档同步

文档职责：

- `README.md`：面向首次接入者的概览和最短路径。
- `bedrockmq-docs/docs/quickstart.md`：完整接入与升级步骤。
- `bedrockmq-docs/docs/api-reference.md`：公共 API 和配置。
- `bedrockmq-docs/docs/database-schema.md`：DDL、状态机、索引、CAS、固定处理期限和恢复。
- `bedrockmq-docs/docs/admin.md`：Admin 部署与 REST API。

代码与文档冲突时先核实测试和当前实现，再修正文档；不要为了匹配过时文档而退化正确代码。
