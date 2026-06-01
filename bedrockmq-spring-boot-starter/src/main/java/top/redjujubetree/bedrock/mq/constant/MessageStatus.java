package top.redjujubetree.bedrock.mq.constant;

public interface MessageStatus {
    int PENDING = 0;
    int PROCESSING = 1;
    int COMPLETED = 2;
    int FAILED = 3;
}
