package top.redjujubetree.bedrock.mq.admin.service;

import org.springframework.stereotype.Service;
import top.redjujubetree.bedrock.mq.admin.dto.MessageSendRequest;
import top.redjujubetree.bedrock.mq.admin.dto.PageResult;
import top.redjujubetree.bedrock.mq.constant.MessageStatus;
import top.redjujubetree.bedrock.mq.entity.BedrockConsumeRecord;
import top.redjujubetree.bedrock.mq.entity.BedrockSubscription;
import top.redjujubetree.bedrock.mq.mapper.BedrockConsumeRecordMapper;
import top.redjujubetree.bedrock.mq.mapper.BedrockSubscriptionMapper;
import top.redjujubetree.bedrock.mq.processor.ProcessorRegistry;
import top.redjujubetree.bedrock.mq.producer.MessageProducer;

import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class BedrockAdminService {

    private final BedrockConsumeRecordMapper consumeRecordMapper;
    private final BedrockSubscriptionMapper subscriptionMapper;
    private final ProcessorRegistry processorRegistry;
    private final MessageProducer messageProducer;

    public BedrockAdminService(BedrockConsumeRecordMapper consumeRecordMapper,
                               BedrockSubscriptionMapper subscriptionMapper,
                               ProcessorRegistry processorRegistry,
                               MessageProducer messageProducer) {
        this.consumeRecordMapper = consumeRecordMapper;
        this.subscriptionMapper = subscriptionMapper;
        this.processorRegistry = processorRegistry;
        this.messageProducer = messageProducer;
    }

    public PageResult<BedrockConsumeRecord> listMessages(String topic, String consumer, Integer status,
                                                          long page, long size) {
        long total = consumeRecordMapper.countMessages(topic, consumer, status);
        long offset = (page - 1) * size;
        List<BedrockConsumeRecord> records = consumeRecordMapper.listMessages(topic, consumer, status, offset, size);
        return new PageResult<>(records, total, page, size);
    }

    public BedrockConsumeRecord getById(Long id) {
        return consumeRecordMapper.selectByIdWithMessage(id);
    }

    /** Re-queue a FAILED consume record back to PENDING, resetting retry count. */
    public boolean retry(Long id) {
        return consumeRecordMapper.resetToPending(id, LocalDateTime.now()) > 0;
    }

    /** Cancel a PENDING consume record, marking it as FAILED. */
    public boolean cancel(Long id) {
        return consumeRecordMapper.cancelPending(id, LocalDateTime.now()) > 0;
    }

    public boolean updateMaxRetry(Long id, int maxRetry) {
        return consumeRecordMapper.updateMaxRetry(id, maxRetry) > 0;
    }

    public int batchUpdateMaxRetry(java.util.List<Long> ids, int maxRetry) {
        if (ids == null || ids.isEmpty()) return 0;
        return consumeRecordMapper.batchUpdateMaxRetry(ids, maxRetry);
    }

    public int batchRetry(java.util.List<Long> ids) {
        if (ids == null || ids.isEmpty()) return 0;
        return consumeRecordMapper.batchResetToPending(ids, LocalDateTime.now());
    }

    public void delete(Long id) {
        consumeRecordMapper.deleteById(id);
    }

    public List<Map<String, Object>> getStats() {
        return consumeRecordMapper.selectStatusCountByTopicAndConsumer();
    }

    public Set<String> getRegisteredProcessors() {
        return subscriptionMapper.findAll().stream()
                .filter(s -> s.getStatus() == 1)
                .map(s -> s.getTopic() + ":" + s.getConsumer())
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
    }

    public Long send(MessageSendRequest req) {
        if (req.getTopic() == null || req.getTopic().trim().isEmpty()) {
            throw new IllegalArgumentException("topic is required");
        }
        if (req.getMessageSource() == null || req.getMessageSource().trim().isEmpty()) {
            throw new IllegalArgumentException("messageSource is required");
        }
        if (req.getPayload() == null || req.getPayload().trim().isEmpty()) {
            throw new IllegalArgumentException("payload is required");
        }
        int maxRetry = req.getMaxRetry() != null ? req.getMaxRetry() : 0;
        if (req.getScheduledAt() != null && !req.getScheduledAt().isEmpty()) {
            LocalDateTime scheduledAt;
            try {
                scheduledAt = LocalDateTime.parse(req.getScheduledAt());
            } catch (DateTimeParseException e) {
                throw new IllegalArgumentException("scheduledAt must be ISO-8601 format, e.g. 2024-01-15T10:30:00");
            }
            return messageProducer.sendAt(req.getTopic(), req.getMessageSource(), req.getPayload(), maxRetry, scheduledAt);
        }
        return messageProducer.send(req.getTopic(), req.getMessageSource(), req.getPayload(), maxRetry);
    }

    // --- Subscription management ---

    public List<BedrockSubscription> listSubscriptions() {
        return subscriptionMapper.findAll();
    }

    public boolean enableSubscription(Long id) {
        return subscriptionMapper.enable(id) > 0;
    }

    public boolean disableSubscription(Long id) {
        return subscriptionMapper.disable(id) > 0;
    }
}
