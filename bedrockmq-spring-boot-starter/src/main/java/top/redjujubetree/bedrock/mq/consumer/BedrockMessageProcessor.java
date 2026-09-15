package top.redjujubetree.bedrock.mq.consumer;

import top.redjujubetree.bedrock.mq.config.BedrockMqProperties;
import top.redjujubetree.bedrock.mq.entity.BedrockConsumeRecord;
import top.redjujubetree.bedrock.mq.entity.BedrockMessage;
import top.redjujubetree.bedrock.mq.mapper.BedrockConsumeRecordMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.LocalDateTime;
import java.util.UUID;

public class BedrockMessageProcessor {

    private static final Logger log = LoggerFactory.getLogger(BedrockMessageProcessor.class);

    private final BedrockConsumeRecordMapper consumeRecordMapper;
    private final BedrockConsumerRegistry registry;
    private final BedrockMqProperties properties;

    public BedrockMessageProcessor(BedrockConsumeRecordMapper consumeRecordMapper,
                                   BedrockConsumerRegistry registry,
                                   BedrockMqProperties properties) {
        this.consumeRecordMapper = consumeRecordMapper;
        this.registry = registry;
        this.properties = properties;
        if (properties.getProcessingTimeoutMinutes() <= 0) {
            throw new IllegalArgumentException(
                    "bedrock.mq.processing-timeout-minutes must be > 0");
        }
    }

    public void process(BedrockConsumeRecord record) {
        BedrockMessageConsumer consumer = registry.getConsumer(record.getTopic(), record.getConsumer());
        if (consumer == null) {
            log.warn("No consumer found for topic={} consumer={}, skipping record id={}",
                    record.getTopic(), record.getConsumer(), record.getId());
            return;
        }

        String processingToken = UUID.randomUUID().toString();
        LocalDateTime processingStartedAt = LocalDateTime.now();
        LocalDateTime processingExpiresAt = processingStartedAt
                .plusMinutes(properties.getProcessingTimeoutMinutes());
        int acquired = consumeRecordMapper.tryAcquire(
                record.getId(), properties.getNodeId(), processingToken,
                processingStartedAt, processingExpiresAt);
        if (acquired == 0) {
            return;
        }

        BedrockMessage messageView = buildMessageView(record);
        try {
            consumer.consume(messageView);
            int updated = consumeRecordMapper.markCompleted(
                    record.getId(), processingToken, LocalDateTime.now());
            if (updated == 1) {
                log.info("Record processed successfully id={} topic={} consumer={}",
                        record.getId(), record.getTopic(), record.getConsumer());
            } else {
                log.warn("Ignored stale successful result because processing ownership was lost: id={}",
                        record.getId());
            }
        } catch (Exception e) {
            log.error("Record processing failed id={} topic={} consumer={} error={}",
                    record.getId(), record.getTopic(), record.getConsumer(), e.getMessage());
            handleFailure(record, processingToken, extractError(e));
        }
    }

    private BedrockMessage buildMessageView(BedrockConsumeRecord record) {
        BedrockMessage view = new BedrockMessage();
        view.setId(record.getMessageId());
        view.setTopic(record.getTopic());
        view.setMessageSource(record.getMessageSource());
        view.setPayload(record.getPayload());
        view.setCreatedAt(record.getMessageCreatedAt());
        view.setUpdatedAt(record.getMessageUpdatedAt());
        return view;
    }

    private void handleFailure(BedrockConsumeRecord record, String processingToken, String errorMsg) {
        int updated = consumeRecordMapper.markFailed(
                record.getId(), processingToken, errorMsg, LocalDateTime.now());
        if (updated == 0) {
            log.warn("Ignored stale failed result because processing ownership was lost: id={}",
                    record.getId());
        }
    }

    private String extractError(Exception e) {
        String msg = e.getMessage();
        if (msg == null || msg.isEmpty()) {
            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            msg = sw.toString();
        }
        return msg.length() > 500 ? msg.substring(0, 500) + "..." : msg;
    }
}
