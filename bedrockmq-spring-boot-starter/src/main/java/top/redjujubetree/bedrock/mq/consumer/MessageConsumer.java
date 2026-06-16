package top.redjujubetree.bedrock.mq.consumer;

import top.redjujubetree.bedrock.mq.config.BedrockMqProperties;
import top.redjujubetree.bedrock.mq.constant.MessageStatus;
import top.redjujubetree.bedrock.mq.entity.BedrockConsumeRecord;
import top.redjujubetree.bedrock.mq.entity.BedrockMessage;
import top.redjujubetree.bedrock.mq.mapper.BedrockConsumeRecordMapper;
import top.redjujubetree.bedrock.mq.processor.MessageProcessor;
import top.redjujubetree.bedrock.mq.processor.ProcessorRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.LocalDateTime;

public class MessageConsumer {

    private static final Logger log = LoggerFactory.getLogger(MessageConsumer.class);

    private final BedrockConsumeRecordMapper consumeRecordMapper;
    private final ProcessorRegistry registry;
    private final BedrockMqProperties properties;

    public MessageConsumer(BedrockConsumeRecordMapper consumeRecordMapper,
                           ProcessorRegistry registry,
                           BedrockMqProperties properties) {
        this.consumeRecordMapper = consumeRecordMapper;
        this.registry = registry;
        this.properties = properties;
    }

    public void consume(BedrockConsumeRecord record) {
        MessageProcessor processor = registry.getProcessor(record.getTopic(), record.getConsumer());
        if (processor == null) {
            log.warn("No processor found for topic={} consumer={}, skipping record id={}",
                    record.getTopic(), record.getConsumer(), record.getId());
            return;
        }

        int acquired = consumeRecordMapper.tryAcquire(record.getId(), properties.getNodeId());
        if (acquired == 0) {
            return;
        }

        BedrockMessage messageView = buildMessageView(record);
        try {
            processor.process(messageView);
            markCompleted(record.getId());
            log.info("Record processed successfully id={} topic={} consumer={}",
                    record.getId(), record.getTopic(), record.getConsumer());
        } catch (Exception e) {
            log.error("Record processing failed id={} topic={} consumer={} error={}",
                    record.getId(), record.getTopic(), record.getConsumer(), e.getMessage());
            handleFailure(record, extractError(e));
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

    private void markCompleted(Long recordId) {
        consumeRecordMapper.markCompleted(recordId, LocalDateTime.now());
    }

    private void handleFailure(BedrockConsumeRecord record, String errorMsg) {
        int nextRetry = record.getRetryCount() + 1;
        int newStatus = nextRetry >= record.getMaxRetry() ? MessageStatus.FAILED : MessageStatus.PENDING;
        consumeRecordMapper.markFailed(record.getId(), newStatus, nextRetry, errorMsg, LocalDateTime.now());
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
