package top.redjujubetree.bedrock.mq.admin.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import top.redjujubetree.bedrock.mq.admin.dto.BatchIdsRequest;
import top.redjujubetree.bedrock.mq.admin.dto.BatchMaxRetryRequest;
import top.redjujubetree.bedrock.mq.admin.dto.MaxRetryRequest;
import top.redjujubetree.bedrock.mq.admin.dto.MessageSendRequest;
import top.redjujubetree.bedrock.mq.admin.service.BedrockAdminService;
import top.redjujubetree.bedrock.mq.entity.BedrockConsumeRecord;

import java.util.Collections;

@RestController
@RequestMapping("/bedrock/messages")
public class BedrockMessageController {

    private final BedrockAdminService service;

    public BedrockMessageController(BedrockAdminService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<?> list(
            @RequestParam(required = false) String topic,
            @RequestParam(required = false) String consumer,
            @RequestParam(required = false) Integer status,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size) {
        return ResponseEntity.ok(service.listMessages(topic, consumer, status, page, size));
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(@PathVariable Long id) {
        BedrockConsumeRecord record = service.getById(id);
        if (record == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(record);
    }

    @PostMapping
    public ResponseEntity<?> send(@RequestBody MessageSendRequest req) {
        try {
            Long id = service.send(req);
            return ResponseEntity.ok(Collections.singletonMap("id", id));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Collections.singletonMap("error", e.getMessage()));
        }
    }

    @PostMapping("/{id}/retry")
    public ResponseEntity<?> retry(@PathVariable Long id) {
        boolean ok = service.retry(id);
        return ok ? ResponseEntity.ok().build() : ResponseEntity.notFound().build();
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<?> cancel(@PathVariable Long id) {
        boolean ok = service.cancel(id);
        return ok ? ResponseEntity.ok().build() : ResponseEntity.notFound().build();
    }

    @PostMapping("/{id}/max-retry")
    public ResponseEntity<?> updateMaxRetry(@PathVariable Long id, @RequestBody MaxRetryRequest req) {
        if (req.getMaxRetry() == null || req.getMaxRetry() < 0) {
            return ResponseEntity.badRequest().body(Collections.singletonMap("error", "maxRetry must be >= 0"));
        }
        boolean ok = service.updateMaxRetry(id, req.getMaxRetry());
        return ok ? ResponseEntity.ok().build() : ResponseEntity.notFound().build();
    }

    @PostMapping("/batch/retry")
    public ResponseEntity<?> batchRetry(@RequestBody BatchIdsRequest req) {
        if (req.getIds() == null || req.getIds().isEmpty()) {
            return ResponseEntity.badRequest().body(Collections.singletonMap("error", "ids are required"));
        }
        int updated = service.batchRetry(req.getIds());
        return ResponseEntity.ok(Collections.singletonMap("updated", updated));
    }

    @PostMapping("/batch/max-retry")
    public ResponseEntity<?> batchUpdateMaxRetry(@RequestBody BatchMaxRetryRequest req) {
        if (req.getIds() == null || req.getIds().isEmpty() || req.getMaxRetry() == null) {
            return ResponseEntity.badRequest().body(Collections.singletonMap("error", "ids and maxRetry are required"));
        }
        if (req.getMaxRetry() < 0) {
            return ResponseEntity.badRequest().body(Collections.singletonMap("error", "maxRetry must be >= 0"));
        }
        int updated = service.batchUpdateMaxRetry(req.getIds(), req.getMaxRetry());
        return ResponseEntity.ok(Collections.singletonMap("updated", updated));
    }

    @PostMapping("/{id}/delete")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.ok().build();
    }
}
