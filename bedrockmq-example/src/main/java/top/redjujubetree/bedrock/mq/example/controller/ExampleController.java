package top.redjujubetree.bedrock.mq.example.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import top.redjujubetree.bedrock.mq.example.dto.NotifyEvent;
import top.redjujubetree.bedrock.mq.example.dto.OrderEvent;
import top.redjujubetree.bedrock.mq.producer.BedrockMessageRequest;
import top.redjujubetree.bedrock.mq.producer.MessageProducer;

import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;

@RestController
@RequestMapping("/example")
public class ExampleController {

    private final MessageProducer producer;

    public ExampleController(MessageProducer producer) {
        this.producer = producer;
    }

    /**
     * Immediate order: fans out to both "order" consumer and "billing" consumer.
     *
     * POST /example/orders?orderId=1&customerId=C001&amount=99.9
     */
    @PostMapping("/orders")
    public ResponseEntity<Map<String, Long>> placeOrder(
            @RequestParam long orderId,
            @RequestParam String customerId,
            @RequestParam double amount) {

        OrderEvent event = new OrderEvent(orderId, customerId, amount);
        Long messageId = producer.send("order", "example-service", event);
        return ResponseEntity.ok(Collections.singletonMap("messageId", messageId));
    }

    /**
     * Delayed order: processed after the given delay in seconds.
     *
     * POST /example/orders/delayed?orderId=2&customerId=C002&amount=50.0&delaySeconds=30
     */
    @PostMapping("/orders/delayed")
    public ResponseEntity<Map<String, Long>> placeDelayedOrder(
            @RequestParam long orderId,
            @RequestParam String customerId,
            @RequestParam double amount,
            @RequestParam(defaultValue = "60") long delaySeconds) {

        OrderEvent event = new OrderEvent(orderId, customerId, amount);
        Long messageId = producer.sendDelayed("order", "example-service", event,
                Duration.ofSeconds(delaySeconds));
        return ResponseEntity.ok(Collections.singletonMap("messageId", messageId));
    }

    /**
     * Notification message on the "notify" topic.
     *
     * POST /example/notify?userId=U001&message=Hello
     */
    @PostMapping("/notify")
    public ResponseEntity<Map<String, Long>> sendNotification(
            @RequestParam String userId,
            @RequestParam String message) {

        NotifyEvent event = new NotifyEvent(userId, message);
        Long messageId = producer.send("notify", "example-service", event);
        return ResponseEntity.ok(Collections.singletonMap("messageId", messageId));
    }

    /**
     * Batch: one order event and one notification in a single transaction.
     *
     * POST /example/batch?orderId=3&customerId=C003&amount=200.0&userId=U003
     */
    @PostMapping("/batch")
    public ResponseEntity<Map<String, String>> sendBatch(
            @RequestParam long orderId,
            @RequestParam String customerId,
            @RequestParam double amount,
            @RequestParam String userId) {

        producer.sendBatch(Arrays.asList(
                new BedrockMessageRequest("order", "example-service",
                        new OrderEvent(orderId, customerId, amount)),
                new BedrockMessageRequest("notify", "example-service",
                        new NotifyEvent(userId, "Your order #" + orderId + " has been placed"))
        ));
        return ResponseEntity.ok(Collections.singletonMap("status", "sent"));
    }
}
