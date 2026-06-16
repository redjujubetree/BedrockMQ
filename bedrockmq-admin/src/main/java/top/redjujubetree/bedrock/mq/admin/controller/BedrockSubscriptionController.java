package top.redjujubetree.bedrock.mq.admin.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import top.redjujubetree.bedrock.mq.admin.dto.MaxRetryRequest;
import top.redjujubetree.bedrock.mq.admin.service.BedrockAdminService;

@RestController
@RequestMapping("/bedrock/subscriptions")
public class BedrockSubscriptionController {

    private final BedrockAdminService service;

    public BedrockSubscriptionController(BedrockAdminService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<?> list() {
        return ResponseEntity.ok(service.listSubscriptions());
    }

    @PostMapping("/{id}/enable")
    public ResponseEntity<?> enable(@PathVariable Long id) {
        boolean ok = service.enableSubscription(id);
        return ok ? ResponseEntity.ok().build() : ResponseEntity.notFound().build();
    }

    @PostMapping("/{id}/disable")
    public ResponseEntity<?> disable(@PathVariable Long id) {
        boolean ok = service.disableSubscription(id);
        return ok ? ResponseEntity.ok().build() : ResponseEntity.notFound().build();
    }

    @PostMapping("/{id}/max-retry")
    public ResponseEntity<?> updateMaxRetry(@PathVariable Long id, @RequestBody MaxRetryRequest req) {
        boolean ok = service.updateSubscriptionMaxRetry(id, req.getMaxRetry());
        return ok ? ResponseEntity.ok().build() : ResponseEntity.notFound().build();
    }
}
