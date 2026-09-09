# Database Schema

BedrockMQ uses three tables.

- **MySQL**: run `bedrockmq-spring-boot-starter/src/main/resources/schema-mysql.sql`
- **SQLite**: run `bedrockmq-spring-boot-starter/src/main/resources/schema-sqlite.sql`

Executable DDL is maintained only in those canonical schema files. This document describes the model and state transitions without duplicating it.

---

## bedrock_message

Immutable message log. Written once by the producer; never updated afterwards.

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

Consumer subscription registry. Registered at application startup by `ConsumerRegistry` for each `@BedrockConsumer` bean. If the row already exists in the database (identified by the `uk_topic_consumer` unique key), it is left unchanged — both `status` and `max_retry` are preserved. The `@BedrockConsumer(maxRetry=N)` value is only used when inserting a new row for the first time.

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

| Column | Type | Notes |
|--------|------|-------|
| id | BIGINT PK | Auto-increment |
| message_id | BIGINT | FK to `bedrock_message.id` |
| topic | VARCHAR(64) | Denormalized from the message for index efficiency |
| consumer | VARCHAR(64) | Consumer name |
| status | TINYINT | State machine: `0`=PENDING → `1`=PROCESSING → `2`=COMPLETED / `3`=FAILED |
| node_id | VARCHAR(128) | Set on CAS acquire; cleared on timeout recovery |
| processing_token | VARCHAR(64) | Unique per execution attempt; required by final state updates |
| processing_started_at | DATETIME | Start time of the current execution attempt |
| processing_expires_at | DATETIME | Fixed timeout for the current attempt; never extended |
| retry_count | INT | Incremented on each failure |
| max_retry | INT | `retry_count + 1 >= max_retry` → FAILED |
| error_msg | VARCHAR(512) | Truncated to 500 chars before write (leaves headroom for DB column limit) |
| scheduled_at | DATETIME | Earliest eligible processing time; enables delayed messages |
| deleted | TINYINT | `0`=normal, `1`=soft-deleted via admin; excluded from polling and admin list queries |
| updated_at | DATETIME | Last state-update time; timeout recovery does not use it |

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
   SET status=1,
       node_id=:nodeId,
       processing_token=:processingToken,
       processing_started_at=:now,
       processing_expires_at=:processingExpiresAt,
       updated_at=:now
 WHERE id=:id AND status=0 AND deleted=0
```

`affected rows = 1` → this node owns the record. `= 0` → another node got there first; skip.

### Fixed processing deadline and timeout recovery

Acquire writes a unique `processing_token` and a `processing_expires_at` equal to the start time plus `processing-timeout-minutes`. The expiry is written once and is never renewed. Completion and failure updates require the same token, preventing a timed-out worker from overwriting a newer execution.

Rows that reach their fixed deadline are reset by `TimeoutRecoveryTask` (runs every 60 s):

```sql
UPDATE bedrock_consume_record
   SET status    = CASE WHEN retry_count + 1 >= max_retry THEN 3 ELSE 0 END,
       retry_count = retry_count + 1,
       node_id = NULL,
       processing_token = NULL,
       processing_started_at = NULL,
       processing_expires_at = NULL,
       error_msg = 'Timeout: processing deadline exceeded',
       updated_at = :now
 WHERE status = 1
   AND processing_expires_at <= :now
   AND deleted = 0
```

This design performs no heartbeat updates, but it requires choosing a timeout long enough for legitimate handlers. A worker crash may remain in PROCESSING until the fixed deadline, while a handler that runs beyond the deadline may overlap with a retry. Token-fenced final updates keep the older execution from overwriting the newer state. Existing databases must run `migration-processing-expires-mysql.sql` or `migration-processing-expires-sqlite.sql` once before deploying this version. PROCESSING rows without a deadline are not recovered automatically.
