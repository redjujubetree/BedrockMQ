package top.redjujubetree.bedrock.mq.producer;

import java.time.LocalDateTime;

public class BedrockMessageRequest {

    private String topic;
    private String messageSource;
    private Object payload;
    private int maxRetry = 0;
    private LocalDateTime scheduledAt;

    public BedrockMessageRequest() {}

    public BedrockMessageRequest(String topic, String messageSource, Object payload) {
        this.topic = topic;
        this.messageSource = messageSource;
        this.payload = payload;
    }

    public String getTopic() { return topic; }
    public void setTopic(String topic) { this.topic = topic; }

    public String getMessageSource() { return messageSource; }
    public void setMessageSource(String messageSource) { this.messageSource = messageSource; }

    public Object getPayload() { return payload; }
    public void setPayload(Object payload) { this.payload = payload; }

    /** 0 means defer to each subscription's configured max_retry. */
    public int getMaxRetry() { return maxRetry; }
    public void setMaxRetry(int maxRetry) { this.maxRetry = maxRetry; }

    public LocalDateTime getScheduledAt() { return scheduledAt; }
    public void setScheduledAt(LocalDateTime scheduledAt) { this.scheduledAt = scheduledAt; }
}
