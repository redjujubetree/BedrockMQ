package top.redjujubetree.bedrock.mq.producer;

import top.redjujubetree.bedrock.mq.constant.MessageStatus;
import top.redjujubetree.bedrock.mq.entity.BedrockConsumeRecord;
import top.redjujubetree.bedrock.mq.entity.BedrockMessage;
import top.redjujubetree.bedrock.mq.entity.BedrockSubscription;
import top.redjujubetree.bedrock.mq.mapper.BedrockConsumeRecordMapper;
import top.redjujubetree.bedrock.mq.mapper.BedrockMessageMapper;
import top.redjujubetree.bedrock.mq.mapper.BedrockSubscriptionMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.transaction.annotation.Transactional;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class MessageProducer {

    private final BedrockMessageMapper messageMapper;
    private final BedrockConsumeRecordMapper consumeRecordMapper;
    private final BedrockSubscriptionMapper subscriptionMapper;
    private final ObjectMapper objectMapper;

    public MessageProducer(BedrockMessageMapper messageMapper,
                           BedrockConsumeRecordMapper consumeRecordMapper,
                           BedrockSubscriptionMapper subscriptionMapper,
                           ObjectMapper objectMapper) {
        this.messageMapper = messageMapper;
        this.consumeRecordMapper = consumeRecordMapper;
        this.subscriptionMapper = subscriptionMapper;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public Long send(String topic, String messageSource, Object payload) {
        return send(topic, messageSource, payload, 0);
    }

    /** maxRetry=0 means use each subscription's configured max_retry. */
    @Transactional
    public Long send(String topic, String messageSource, Object payload, int maxRetry) {
        return sendAt(topic, messageSource, payload, maxRetry, LocalDateTime.now());
    }

    @Transactional
    public Long sendAt(String topic, String messageSource, Object payload, int maxRetry, LocalDateTime scheduledAt) {
        BedrockMessage message = buildMessage(topic, messageSource, payload);
        messageMapper.insert(message);
        createConsumeRecords(message.getId(), topic, maxRetry, scheduledAt);
        return message.getId();
    }

    @Transactional
    public Long sendDelayed(String topic, String messageSource, Object payload, Duration delay) {
        return sendAt(topic, messageSource, payload, 0, LocalDateTime.now().plus(delay));
    }

    @Transactional
    public void sendBatch(List<BedrockMessageRequest> requests) {
        List<BedrockMessage> messages = new ArrayList<>(requests.size());
        for (BedrockMessageRequest req : requests) {
            messages.add(buildMessage(req.getTopic(), req.getMessageSource(), req.getPayload()));
        }
        messageMapper.insertBatch(messages);

        List<BedrockConsumeRecord> allRecords = new ArrayList<>();
        for (int i = 0; i < requests.size(); i++) {
            BedrockMessageRequest req = requests.get(i);
            LocalDateTime scheduledAt = req.getScheduledAt() != null ? req.getScheduledAt() : LocalDateTime.now();
            Long messageId = messages.get(i).getId();
            List<BedrockSubscription> subs = subscriptionMapper.findEnabledByTopic(req.getTopic());
            for (BedrockSubscription sub : subs) {
                int retry = req.getMaxRetry() > 0 ? req.getMaxRetry() : sub.getMaxRetry();
                allRecords.add(buildConsumeRecord(messageId, req.getTopic(), sub.getConsumer(), retry, scheduledAt));
            }
        }
        if (!allRecords.isEmpty()) {
            consumeRecordMapper.insertBatch(allRecords);
        }
    }

    private void createConsumeRecords(Long messageId, String topic, int maxRetry, LocalDateTime scheduledAt) {
        List<BedrockSubscription> subs = subscriptionMapper.findEnabledByTopic(topic);
        if (subs.isEmpty()) {
            return;
        }
        List<BedrockConsumeRecord> records = subs.stream()
                .map(sub -> {
                    int retry = maxRetry > 0 ? maxRetry : sub.getMaxRetry();
                    return buildConsumeRecord(messageId, topic, sub.getConsumer(), retry, scheduledAt);
                })
                .collect(Collectors.toList());
        consumeRecordMapper.insertBatch(records);
    }

    private BedrockMessage buildMessage(String topic, String messageSource, Object payload) {
        if (topic == null || topic.isEmpty()) {
            throw new IllegalArgumentException("topic must not be null or empty");
        }
        if (messageSource == null || messageSource.isEmpty()) {
            throw new IllegalArgumentException("messageSource must not be null or empty");
        }
        LocalDateTime now = LocalDateTime.now();
        BedrockMessage message = new BedrockMessage();
        message.setTopic(topic);
        message.setMessageSource(messageSource);
        message.setPayload(toJson(payload));
        message.setCreatedAt(now);
        message.setUpdatedAt(now);
        return message;
    }

    private BedrockConsumeRecord buildConsumeRecord(Long messageId, String topic, String consumer,
                                                     int maxRetry, LocalDateTime scheduledAt) {
        LocalDateTime now = LocalDateTime.now();
        BedrockConsumeRecord record = new BedrockConsumeRecord();
        record.setMessageId(messageId);
        record.setTopic(topic);
        record.setConsumer(consumer);
        record.setStatus(MessageStatus.PENDING);
        record.setRetryCount(0);
        record.setMaxRetry(maxRetry);
        record.setScheduledAt(scheduledAt);
        record.setCreatedAt(now);
        record.setUpdatedAt(now);
        return record;
    }

    private String toJson(Object payload) {
        if (payload instanceof String) {
            return (String) payload;
        }
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to serialize payload", e);
        }
    }
}
