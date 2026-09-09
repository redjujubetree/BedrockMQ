package top.redjujubetree.bedrock.mq.example.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.redjujubetree.bedrock.mq.annotation.BedrockConsumer;
import top.redjujubetree.bedrock.mq.entity.BedrockMessage;
import top.redjujubetree.bedrock.mq.example.dto.OrderEvent;
import top.redjujubetree.bedrock.mq.consumer.MessageConsumer;

/**
 * Pub-sub fan-out: same "order" topic, independent consumer "billing".
 * Each order message produces one consume record for "order" and one for "billing".
 * maxRetry=5 overrides the default of 3.
 */
@BedrockConsumer(value = "billing", topic = "order", maxRetry = 5)
public class BillingConsumer implements MessageConsumer {

    private static final Logger log = LoggerFactory.getLogger(BillingConsumer.class);

    private final ObjectMapper objectMapper;

    public BillingConsumer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void consume(BedrockMessage message) throws Exception {
        OrderEvent event = objectMapper.readValue(message.getPayload(), OrderEvent.class);
        log.info("[BILLING] Charging customer={} amount={} for order#{}",
                event.getCustomerId(), event.getAmount(), event.getOrderId());
        // billing logic here
    }
}
