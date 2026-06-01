package top.redjujubetree.bedrock.mq.admin.dto;

import lombok.Data;

import java.util.List;

@Data
public class BatchIdsRequest {
    private List<Long> ids;
}
