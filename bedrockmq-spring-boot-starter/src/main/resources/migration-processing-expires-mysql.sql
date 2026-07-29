-- Run once when upgrading an existing BedrockMQ database to fixed processing deadlines.
ALTER TABLE bedrock_consume_record
    ADD COLUMN processing_token VARCHAR(64) NULL COMMENT '本次执行的唯一所有权令牌' AFTER node_id,
    ADD COLUMN processing_started_at DATETIME NULL COMMENT '本次执行开始时间' AFTER processing_token,
    ADD COLUMN processing_expires_at DATETIME NULL COMMENT '本次执行的固定超时时间' AFTER processing_started_at;

CREATE INDEX idx_status_processing_expires
    ON bedrock_consume_record (status, processing_expires_at, deleted);
