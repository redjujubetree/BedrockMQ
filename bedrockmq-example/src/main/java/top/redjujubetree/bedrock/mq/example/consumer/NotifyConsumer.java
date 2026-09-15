package top.redjujubetree.bedrock.mq.example.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.redjujubetree.bedrock.mq.annotation.BedrockConsumer;
import top.redjujubetree.bedrock.mq.entity.BedrockMessage;
import top.redjujubetree.bedrock.mq.example.dto.NotifyEvent;
import top.redjujubetree.bedrock.mq.consumer.BedrockMessageConsumer;

/** Handles push notifications on a separate "notify" topic. */
@BedrockConsumer(value = "notify", topic = "notify")
public class NotifyConsumer implements BedrockMessageConsumer {

    private static final Logger log = LoggerFactory.getLogger(NotifyConsumer.class);

    private final ObjectMapper objectMapper;

    public NotifyConsumer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void consume(BedrockMessage message) throws Exception {
        NotifyEvent event = objectMapper.readValue(message.getPayload(), NotifyEvent.class);
        log.info("[NOTIFY] Sending notification to user={}: {}", event.getUserId(), event.getMessage());
        // push notification logic here
    }
}
