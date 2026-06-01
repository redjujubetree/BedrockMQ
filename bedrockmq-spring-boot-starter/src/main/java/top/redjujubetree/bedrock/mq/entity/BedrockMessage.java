package top.redjujubetree.bedrock.mq.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class BedrockMessage {

    private Long id;

    private String topic;

    private String messageSource;

    private String payload;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
