package top.redjujubetree.bedrock.mq.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import top.redjujubetree.bedrock.mq.client.EncryptedMessage;
import top.redjujubetree.bedrock.mq.client.HttpMessageRequest;
import top.redjujubetree.bedrock.mq.client.MessageCipher;
import top.redjujubetree.bedrock.mq.config.BedrockMqProperties;
import top.redjujubetree.bedrock.mq.producer.MessageProducer;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/** Optional HTTP ingress, registered only by BedrockAutoConfiguration in web applications. */
@RestController
public class BedrockMessageController {
    private final Map<String, MessageCipher> ciphers = new HashMap<>();
    private final MessageCipher defaultCipher;
    private final ObjectMapper mapper;
    private final MessageProducer producer;

    public BedrockMessageController(BedrockMqProperties.Server settings, ObjectMapper mapper,
                                    MessageProducer producer) {
        this.defaultCipher = settings.getEncryptionKey() == null || settings.getEncryptionKey().isEmpty()
                ? null : new MessageCipher(settings.getEncryptionKey(), settings.getAlgorithm());
        if (settings.getClients() != null) {
            for (Map.Entry<String, BedrockMqProperties.Credential> entry : settings.getClients().entrySet()) {
                if (entry.getKey() == null || entry.getKey().trim().isEmpty() || entry.getValue() == null) {
                    throw new IllegalArgumentException("Invalid HTTP client configuration");
                }
                BedrockMqProperties.Credential credential = entry.getValue();
                String key = credential.getEncryptionKey() == null || credential.getEncryptionKey().isEmpty()
                        ? settings.getEncryptionKey() : credential.getEncryptionKey();
                String algorithm = credential.getAlgorithm() == null || credential.getAlgorithm().isEmpty()
                        ? settings.getAlgorithm() : credential.getAlgorithm();
                ciphers.put(entry.getKey(), new MessageCipher(key, algorithm));
            }
        }
        if (defaultCipher == null && ciphers.isEmpty()) {
            throw new IllegalArgumentException("Configure an HTTP server encryption key or client credentials");
        }
        this.mapper = mapper;
        this.producer = producer;
    }

    @PostMapping("/bedrock/v1/messages")
    public ResponseEntity<?> send(@RequestBody EncryptedMessage message) {
        try {
            if (message == null || message.getClient() == null || message.getClient().trim().isEmpty()) {
                throw new IllegalArgumentException("Client name is required");
            }
            MessageCipher cipher = ciphers.get(message.getClient());
            if (cipher == null) {
                cipher = defaultCipher;
            }
            if (cipher == null) {
                throw new IllegalArgumentException("Unknown HTTP client");
            }
            HttpMessageRequest request = mapper.readValue(cipher.decrypt(message), HttpMessageRequest.class);
            if (request.getTopic() == null || request.getTopic().trim().isEmpty()
                    || request.getMessageSource() == null || request.getMessageSource().trim().isEmpty()
                    || request.getPayload() == null || request.getPayload().trim().isEmpty()
                    || (request.getMaxRetry() != null && request.getMaxRetry() < 0)) {
                throw new IllegalArgumentException("Invalid message fields");
            }
            int maxRetry = request.getMaxRetry() == null ? 0 : request.getMaxRetry();
            Long id;
            if (request.getScheduledAt() == null || request.getScheduledAt().isEmpty()) {
                id = producer.send(request.getTopic(), request.getMessageSource(), request.getPayload(), maxRetry);
            } else {
                LocalDateTime scheduledAt = LocalDateTime.parse(request.getScheduledAt());
                id = producer.sendAt(request.getTopic(), request.getMessageSource(), request.getPayload(),
                        maxRetry, scheduledAt);
            }
            return ResponseEntity.ok(Collections.singletonMap("id", id));
        } catch (IllegalArgumentException | IOException | DateTimeParseException e) {
            return ResponseEntity.badRequest().body(Collections.singletonMap("error", "Invalid message message or request"));
        }
    }
}
