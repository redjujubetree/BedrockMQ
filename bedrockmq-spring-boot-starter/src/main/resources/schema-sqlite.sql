-- SQLite 不支持 MySQL 的列级 COMMENT 语法；以下行注释用于说明表、字段和索引用途。

-- 消息表：保存发布后不可变的消息内容。
CREATE TABLE IF NOT EXISTS bedrock_message (
    id             INTEGER      NOT NULL PRIMARY KEY AUTOINCREMENT, -- 自增主键
    topic          VARCHAR(64)  NOT NULL,                           -- 消息主题
    message_source VARCHAR(64)  NOT NULL,                           -- 消息发送方
    payload        TEXT         NOT NULL,                           -- JSON 格式业务数据
    created_at     DATETIME     NOT NULL DEFAULT (datetime('now')), -- 创建时间
    updated_at     DATETIME     NOT NULL DEFAULT (datetime('now'))  -- 更新时间，由 Java 显式维护
);

-- 按主题查询消息。
CREATE INDEX IF NOT EXISTS idx_topic ON bedrock_message (topic);

-- 消费者订阅表：定义 topic 到 consumer 的持久化订阅关系。
CREATE TABLE IF NOT EXISTS bedrock_subscription (
    id         INTEGER      NOT NULL PRIMARY KEY AUTOINCREMENT, -- 自增主键
    topic      VARCHAR(64)  NOT NULL,                           -- 消息主题
    consumer   VARCHAR(64)  NOT NULL,                           -- 消费者标识
    max_retry  INT          NOT NULL DEFAULT 3,                 -- 最大执行次数，包含首次执行
    status     TINYINT      NOT NULL DEFAULT 1,                 -- 1=启用 0=停用
    created_at DATETIME     NOT NULL DEFAULT (datetime('now')), -- 创建时间
    updated_at DATETIME     NOT NULL DEFAULT (datetime('now'))  -- 更新时间，由 Java 显式维护
);

-- 同一 topic 下的 consumer 只能注册一次。
CREATE UNIQUE INDEX IF NOT EXISTS uk_topic_consumer ON bedrock_subscription (topic, consumer);

-- 消息消费记录表：保存每条消息对每个 consumer 的独立消费状态。
CREATE TABLE IF NOT EXISTS bedrock_consume_record (
    id           INTEGER      NOT NULL PRIMARY KEY AUTOINCREMENT, -- 自增主键
    message_id   BIGINT       NOT NULL,                           -- 关联消息 ID
    topic        VARCHAR(64)  NOT NULL,                           -- 消息主题
    consumer     VARCHAR(64)  NOT NULL,                           -- 消费者标识
    status       TINYINT      NOT NULL DEFAULT 0,                 -- 0=PENDING 1=PROCESSING 2=COMPLETED 3=FAILED
    node_id      VARCHAR(128),                                    -- 正在处理的节点标识
    processing_token      VARCHAR(64),                            -- 本次执行的唯一所有权令牌
    processing_started_at DATETIME,                               -- 本次执行开始时间
    processing_expires_at DATETIME,                               -- 本次执行的固定超时时间
    retry_count  INT          NOT NULL DEFAULT 0,                 -- 当前重试次数
    max_retry    INT          NOT NULL DEFAULT 3,                 -- 最大执行次数，包含首次执行
    error_msg    VARCHAR(512),                                    -- 失败原因
    scheduled_at DATETIME     NOT NULL DEFAULT (datetime('now')), -- 最早可处理时间
    deleted      TINYINT      NOT NULL DEFAULT 0,                 -- 0=正常 1=已删除（逻辑删除）
    created_at   DATETIME     NOT NULL DEFAULT (datetime('now')), -- 创建时间
    updated_at   DATETIME     NOT NULL DEFAULT (datetime('now'))  -- 更新时间，由 Java 显式维护
);

-- 每条消息对同一个 consumer 只生成一条消费记录。
CREATE UNIQUE INDEX IF NOT EXISTS uk_msg_consumer
    ON bedrock_consume_record (message_id, consumer);

-- 支持按 topic、consumer、状态和调度时间轮询待消费记录。
CREATE INDEX IF NOT EXISTS idx_topic_consumer_status_scheduled
    ON bedrock_consume_record (topic, consumer, status, scheduled_at);

-- 支持扫描达到固定处理期限的 PROCESSING 记录。
CREATE INDEX IF NOT EXISTS idx_status_processing_expires
    ON bedrock_consume_record (status, processing_expires_at, deleted);
