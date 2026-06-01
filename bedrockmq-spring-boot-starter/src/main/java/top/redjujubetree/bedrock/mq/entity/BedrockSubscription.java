package top.redjujubetree.bedrock.mq.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class BedrockSubscription {

    private Long id;

    private String topic;

    private String consumer;

    private Integer maxRetry;

    /** 1=enabled, 0=disabled. */
    private Integer status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
