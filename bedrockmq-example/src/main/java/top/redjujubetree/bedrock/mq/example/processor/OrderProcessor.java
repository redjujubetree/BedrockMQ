package top.redjujubetree.bedrock.mq.example.processor;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.redjujubetree.bedrock.mq.annotation.BedrockConsumer;
import top.redjujubetree.bedrock.mq.entity.BedrockMessage;
import top.redjujubetree.bedrock.mq.example.dto.OrderEvent;
import top.redjujubetree.bedrock.mq.processor.MessageProcessor;

/** Handles core order fulfillment logic. */
@BedrockConsumer(value = "order", topic = "order")
public class OrderProcessor implements MessageProcessor {

    private static final Logger log = LoggerFactory.getLogger(OrderProcessor.class);

    private final ObjectMapper objectMapper;

    public OrderProcessor(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void process(BedrockMessage message) throws Exception {
        OrderEvent event = objectMapper.readValue(message.getPayload(), OrderEvent.class);
        log.info("[ORDER] Processing order#{} for customer={} amount={}",
                event.getOrderId(), event.getCustomerId(), event.getAmount());
        // business logic here
    }
}
