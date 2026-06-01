package top.redjujubetree.bedrock.mq.admin.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import top.redjujubetree.bedrock.mq.admin.service.BedrockAdminService;

@RestController
@RequestMapping("/bedrock")
public class BedrockStatsController {

    private final BedrockAdminService service;

    public BedrockStatsController(BedrockAdminService service) {
        this.service = service;
    }

    @GetMapping("/stats")
    public ResponseEntity<?> stats() {
        return ResponseEntity.ok(service.getStats());
    }

    @GetMapping("/processors")
    public ResponseEntity<?> processors() {
        return ResponseEntity.ok(service.getRegisteredProcessors());
    }
}
