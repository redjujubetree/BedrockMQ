package top.redjujubetree.bedrock.mq.example.controller;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import top.redjujubetree.bedrock.mq.client.BedrockHttpClient;
import top.redjujubetree.bedrock.mq.example.dto.OrderEvent;

import java.io.IOException;
import java.util.Collections;
import java.util.Map;

/** Example sender registered only when the optional HTTP client is enabled. */
@RestController
@RequestMapping("/example/http")
@ConditionalOnProperty(prefix = "bedrock.mq.http.client", name = "enabled", havingValue = "true")
public class HttpClientExampleController {

    private final BedrockHttpClient client;

    public HttpClientExampleController(BedrockHttpClient client) {
        this.client = client;
    }

    /** POST /example/http/orders?orderId=1&customerId=C001&amount=99.9 */
    @PostMapping("/orders")
    public ResponseEntity<Map<String, Long>> sendOrder(
            @RequestParam long orderId,
            @RequestParam String customerId,
            @RequestParam double amount) throws IOException {
        Long messageId = client.send("primary", "order", "http-example-service",
                new OrderEvent(orderId, customerId, amount));
        return ResponseEntity.ok(Collections.singletonMap("messageId", messageId));
    }
}
