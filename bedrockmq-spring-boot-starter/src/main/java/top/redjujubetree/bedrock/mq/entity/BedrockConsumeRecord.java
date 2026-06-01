package top.redjujubetree.bedrock.mq.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class BedrockConsumeRecord {

    private Long id;

    private Long messageId;

    private String topic;

    private String consumer;

    private Integer status;

    private String nodeId;

    private Integer retryCount;

    private Integer maxRetry;

    private String errorMsg;

    private LocalDateTime scheduledAt;

    private Integer deleted;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    /** Populated by JOIN query with bedrock_message — not stored in DB. */
    private String payload;

    /** Populated by JOIN query with bedrock_message — not stored in DB. */
    private String messageSource;
}
