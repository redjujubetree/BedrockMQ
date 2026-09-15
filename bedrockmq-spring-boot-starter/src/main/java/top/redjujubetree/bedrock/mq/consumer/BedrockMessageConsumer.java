package top.redjujubetree.bedrock.mq.consumer;

import top.redjujubetree.bedrock.mq.entity.BedrockMessage;

public interface BedrockMessageConsumer {
    void consume(BedrockMessage message) throws Exception;
}
