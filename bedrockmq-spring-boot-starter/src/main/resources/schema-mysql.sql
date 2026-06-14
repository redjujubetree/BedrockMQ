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
