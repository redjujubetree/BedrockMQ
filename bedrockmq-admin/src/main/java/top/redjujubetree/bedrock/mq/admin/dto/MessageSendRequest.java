package top.redjujubetree.bedrock.mq.admin.dto;

import lombok.Data;

@Data
public class MessageSendRequest {
    private String topic;
    private String messageSource;
    private String payload;
    private Integer maxRetry;
    /** ISO-8601 datetime without timezone, e.g. "2024-01-15T10:30:00". Null means send immediately. */
    private String scheduledAt;
}
