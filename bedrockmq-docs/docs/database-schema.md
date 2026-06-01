# Database Schema

BedrockMQ uses three tables. Run `bedrockmq-spring-boot-starter/src/main/resources/schema.sql` to initialize them.

---

## bedrock_message

Immutable message log. Written once by the producer; never updated afterwards.

```sql
CREATE TABLE IF NOT EXISTS bedrock_message (
    id             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    topic          VARCHAR(64)  NOT NULL COMMENT '消息主题',
    message_source VARCHAR(64)  NOT NULL COMMENT '消息发送方',
    payload        TEXT         NOT NULL COMMENT 'JSON 格式业务数据',
    created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    INDEX idx_topic (topic)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='消息表（不可变）';
```

| Column | Type | Notes |
|--------|------|-------|
| id | BIGINT PK | Auto-increment |
| topic | VARCHAR(64) | Routes to all subscribers of this topic |
| message_source | VARCHAR(64) NOT NULL | Sending service name; required |
| payload | TEXT | JSON business data |
| created_at | DATETIME | Set by `MessageProducer` at insert time |
| updated_at | DATETIME | Set by `MessageProducer` at insert time; DDL also has `ON UPDATE` |

---

## bedrock_subscription

Consumer subscription registry. Upserted at application startup by `ProcessorRegistry` for each `@BedrockConsumer` bean. Persists across restarts; `status` is not overwritten on upsert so admin-disabled subscriptions survive restarts.

```sql
CREATE TABLE IF NOT EXISTS bedrock_subscription (
    id         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    topic      VARCHAR(64)  NOT NULL COMMENT '消息主题',
    consumer   VARCHAR(64)  NOT NULL COMMENT '消费者标识',
    max_retry  INT          NOT NULL DEFAULT 3 COMMENT '最大重试次数（含首次）',
    status     TINYINT      NOT NULL DEFAULT 1 COMMENT '1=启用 0=停用',
    created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_topic_consumer (topic, consumer)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='消费者订阅表';
```

| Column | Type | Notes |
|--------|------|-------|
| id | BIGINT PK | Auto-increment |
| topic | VARCHAR(64) | Subscribed topic |
| consumer | VARCHAR(64) | Consumer name (`@BedrockConsumer.value()`) |
| max_retry | INT | Default retry limit; overridable per message at produce time |
| status | TINYINT | `1`=enabled, `0`=disabled. Disabled subscriptions receive no new consume records |

Unique constraint `uk_topic_consumer` ensures one row per (topic, consumer) pair.

---

## bedrock_consume_record

Per-consumer consumption state. One row is created for each enabled subscriber at produce time. All state transitions happen here; `bedrock_message` is never modified.

```sql
CREATE TABLE IF NOT EXISTS bedrock_consume_record (
    id           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    message_id   BIGINT       NOT NULL COMMENT '关联消息ID',
    topic        VARCHAR(64)  NOT NULL COMMENT '消息主题',
    consumer     VARCHAR(64)  NOT NULL COMMENT '消费者标识',
    status       TINYINT      NOT NULL DEFAULT 0 COMMENT '0=PENDING 1=PROCESSING 2=COMPLETED 3=FAILED',
    node_id      VARCHAR(128)          COMMENT '正在处理的节点标识',
    retry_count  INT          NOT NULL DEFAULT 0 COMMENT '当前重试次数',
    max_retry    INT          NOT NULL DEFAULT 3 COMMENT '最大执行次数（含首次）',
    error_msg    VARCHAR(512)          COMMENT '失败原因',
    scheduled_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '最早可处理时间',
    deleted      TINYINT      NOT NULL DEFAULT 0 COMMENT '0=正常 1=已删除（逻辑删除）',
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_msg_consumer (message_id, consumer),
    INDEX idx_topic_consumer_status_scheduled (topic, consumer, status, scheduled_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='消息消费记录表';
```

| Column | Type | Notes |
|--------|------|-------|
| id | BIGINT PK | Auto-increment |
| message_id | BIGINT | FK to `bedrock_message.id` |
| topic | VARCHAR(64) | Denormalized from the message for index efficiency |
| consumer | VARCHAR(64) | Consumer name |
| status | TINYINT | State machine: `0`=PENDING → `1`=PROCESSING → `2`=COMPLETED / `3`=FAILED |
| node_id | VARCHAR(128) | Set on CAS acquire; cleared on timeout recovery |
| retry_count | INT | Incremented on each failure |
| max_retry | INT | `retry_count + 1 >= max_retry` → FAILED |
| error_msg | VARCHAR(512) | Truncated to 512 chars before write |
| scheduled_at | DATETIME | Earliest eligible processing time; enables delayed messages |
| deleted | TINYINT | `0`=normal, `1`=soft-deleted via admin; excluded from polling and admin list queries |
| updated_at | DATETIME | Used by timeout recovery to detect stale PROCESSING rows |

### Status transitions

```
PENDING(0) ──CAS acquire──→ PROCESSING(1) ──success──→ COMPLETED(2)
    ↑                              │
    │          failure, retry_count+1 < max_retry
    └──────────────────────────────┘
                                   │
               failure, retry_count+1 >= max_retry
                                   ↓
                              FAILED(3)
```

### Key index

`idx_topic_consumer_status_scheduled (topic, consumer, status, scheduled_at)` is designed for the polling query:

```sql
SELECT ... WHERE topic=? AND consumer=? AND status=0 AND scheduled_at <= NOW()
ORDER BY scheduled_at ASC LIMIT ?
```

Equality columns (topic, consumer, status) come first; the range/sort column (scheduled_at) comes last, enabling an index range scan per (topic, consumer) pair.

### CAS acquire

Distributed mutex without Redis:

```sql
UPDATE bedrock_consume_record
   SET status=1, node_id=#{nodeId}, updated_at=NOW()
 WHERE id=#{id} AND status=0
```

`affected rows = 1` → this node owns the record. `= 0` → another node got there first; skip.

### Timeout recovery

Rows stuck in PROCESSING for longer than `bedrock.processing-timeout-minutes` are reset by `TimeoutRecoveryTask` (runs every 60 s):

```sql
UPDATE bedrock_consume_record
   SET status    = CASE WHEN retry_count+1 >= max_retry THEN 3 ELSE 0 END,
       retry_count = retry_count + 1,
       node_id   = NULL,
       error_msg = 'Timeout: processing node may have crashed',
       updated_at = NOW()
 WHERE status = 1
   AND updated_at < DATE_SUB(NOW(), INTERVAL #{minutes} MINUTE)
```
