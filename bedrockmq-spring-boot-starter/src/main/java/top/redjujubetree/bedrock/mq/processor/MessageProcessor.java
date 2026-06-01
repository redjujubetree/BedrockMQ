package top.redjujubetree.bedrock.mq.processor;

import top.redjujubetree.bedrock.mq.entity.BedrockMessage;

public interface MessageProcessor {
    void process(BedrockMessage message) throws Exception;
}
