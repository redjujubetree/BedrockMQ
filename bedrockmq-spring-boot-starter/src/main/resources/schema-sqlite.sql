CREATE TABLE IF NOT EXISTS bedrock_message (
    id             INTEGER      NOT NULL PRIMARY KEY AUTOINCREMENT,
    topic          VARCHAR(64)  NOT NULL,
    message_source VARCHAR(64)  NOT NULL,
    payload        TEXT         NOT NULL,
    created_at     DATETIME     NOT NULL DEFAULT (datetime('now')),
    updated_at     DATETIME     NOT NULL DEFAULT (datetime('now'))
);

CREATE INDEX IF NOT EXISTS idx_topic ON bedrock_message (topic);

CREATE TABLE IF NOT EXISTS bedrock_subscription (
    id         INTEGER      NOT NULL PRIMARY KEY AUTOINCREMENT,
    topic      VARCHAR(64)  NOT NULL,
    consumer   VARCHAR(64)  NOT NULL,
    max_retry  INT          NOT NULL DEFAULT 3,
    status     TINYINT      NOT NULL DEFAULT 1,
    created_at DATETIME     NOT NULL DEFAULT (datetime('now')),
    updated_at DATETIME     NOT NULL DEFAULT (datetime('now'))
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_topic_consumer ON bedrock_subscription (topic, consumer);

CREATE TABLE IF NOT EXISTS bedrock_consume_record (
    id           INTEGER      NOT NULL PRIMARY KEY AUTOINCREMENT,
    message_id   BIGINT       NOT NULL,
    topic        VARCHAR(64)  NOT NULL,
    consumer     VARCHAR(64)  NOT NULL,
    status       TINYINT      NOT NULL DEFAULT 0,
    node_id      VARCHAR(128),
    retry_count  INT          NOT NULL DEFAULT 0,
    max_retry    INT          NOT NULL DEFAULT 3,
    error_msg    VARCHAR(512),
    scheduled_at DATETIME     NOT NULL DEFAULT (datetime('now')),
    deleted      TINYINT      NOT NULL DEFAULT 0,
    created_at   DATETIME     NOT NULL DEFAULT (datetime('now')),
    updated_at   DATETIME     NOT NULL DEFAULT (datetime('now'))
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_msg_consumer
    ON bedrock_consume_record (message_id, consumer);

CREATE INDEX IF NOT EXISTS idx_topic_consumer_status_scheduled
    ON bedrock_consume_record (topic, consumer, status, scheduled_at);
