-- Run once when upgrading an existing BedrockMQ database to fixed processing deadlines.
ALTER TABLE bedrock_consume_record ADD COLUMN processing_token VARCHAR(64);
ALTER TABLE bedrock_consume_record ADD COLUMN processing_started_at DATETIME;
ALTER TABLE bedrock_consume_record ADD COLUMN processing_expires_at DATETIME;

CREATE INDEX IF NOT EXISTS idx_status_processing_expires
    ON bedrock_consume_record (status, processing_expires_at, deleted);
