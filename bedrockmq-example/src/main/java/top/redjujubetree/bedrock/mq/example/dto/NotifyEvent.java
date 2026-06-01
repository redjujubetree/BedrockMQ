package top.redjujubetree.bedrock.mq.example.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class NotifyEvent {
    private String userId;
    private String message;
}
