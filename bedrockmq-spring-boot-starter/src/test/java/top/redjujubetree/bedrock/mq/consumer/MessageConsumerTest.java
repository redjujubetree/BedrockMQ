package top.redjujubetree.bedrock.mq.consumer;

import top.redjujubetree.bedrock.mq.config.BedrockMqProperties;
import top.redjujubetree.bedrock.mq.constant.MessageStatus;
import top.redjujubetree.bedrock.mq.entity.BedrockConsumeRecord;
import top.redjujubetree.bedrock.mq.entity.BedrockMessage;
import top.redjujubetree.bedrock.mq.mapper.BedrockConsumeRecordMapper;
import top.redjujubetree.bedrock.mq.processor.MessageProcessor;
import top.redjujubetree.bedrock.mq.processor.ProcessorRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MessageConsumerTest {

    @Mock BedrockConsumeRecordMapper consumeRecordMapper;
    @Mock ProcessorRegistry registry;
    @Mock BedrockMqProperties properties;
    @Mock MessageProcessor processor;

    MessageConsumer consumer;

    @BeforeEach
    void setUp() {
        lenient().when(properties.getNodeId()).thenReturn("test-node");
        consumer = new MessageConsumer(consumeRecordMapper, registry, properties);
    }

    private BedrockConsumeRecord buildRecord(int retryCount, int maxRetry) {
        BedrockConsumeRecord record = new BedrockConsumeRecord();
        record.setId(1L);
        record.setMessageId(10L);
        record.setTopic("order");
        record.setConsumer("order");
        record.setStatus(MessageStatus.PENDING);
        record.setRetryCount(retryCount);
        record.setMaxRetry(maxRetry);
        record.setPayload("{\"id\":1}");
        record.setMessageSource("shop");
        return record;
    }

    @Test
    void consume_skipsAllProcessingWhenCasAcquireFails() {
        when(registry.getProcessor("order", "order")).thenReturn(processor);
        when(consumeRecordMapper.tryAcquire(1L, "test-node")).thenReturn(0);

        consumer.consume(buildRecord(0, 3));

        verify(consumeRecordMapper, never()).markCompleted(anyLong(), any());
    }

    @Test
    void consume_skipsAcquireWhenNoProcessorRegistered() {
        when(registry.getProcessor("order", "order")).thenReturn(null);

        consumer.consume(buildRecord(0, 3));

        verify(consumeRecordMapper, never()).tryAcquire(anyLong(), anyString());
        verify(consumeRecordMapper, never()).markCompleted(anyLong(), any());
    }

    @Test
    void consume_marksCompletedAndClearsErrorMsgOnSuccess() throws Exception {
        when(consumeRecordMapper.tryAcquire(1L, "test-node")).thenReturn(1);
        when(registry.getProcessor("order", "order")).thenReturn(processor);

        consumer.consume(buildRecord(0, 3));

        verify(processor).process(any(BedrockMessage.class));
        verify(consumeRecordMapper).markCompleted(eq(1L), any(LocalDateTime.class));
    }

    @Test
    void consume_resetsStatusToPendingWhenRetriesAreRemaining() throws Exception {
        when(consumeRecordMapper.tryAcquire(1L, "test-node")).thenReturn(1);
        when(registry.getProcessor("order", "order")).thenReturn(processor);
        doThrow(new RuntimeException("db timeout")).when(processor).process(any());

        // retryCount=0, maxRetry=3 → nextRetry(1) < maxRetry(3) → back to PENDING
        consumer.consume(buildRecord(0, 3));

        verify(consumeRecordMapper).markFailed(eq(1L), eq(MessageStatus.PENDING), eq(1), any(), any(LocalDateTime.class));
    }

    @Test
    void consume_marksFailedWhenMaxRetriesAreExhausted() throws Exception {
        when(consumeRecordMapper.tryAcquire(1L, "test-node")).thenReturn(1);
        when(registry.getProcessor("order", "order")).thenReturn(processor);
        doThrow(new RuntimeException("db timeout")).when(processor).process(any());

        // retryCount=2, maxRetry=3 → nextRetry(3) >= maxRetry(3) → FAILED
        consumer.consume(buildRecord(2, 3));

        verify(consumeRecordMapper).markFailed(eq(1L), eq(MessageStatus.FAILED), eq(3), any(), any(LocalDateTime.class));
    }

    @Test
    void consume_handlesNullPointerExceptionWithNoMessageByFallingBackToStackTrace() throws Exception {
        when(consumeRecordMapper.tryAcquire(1L, "test-node")).thenReturn(1);
        when(registry.getProcessor("order", "order")).thenReturn(processor);
        doThrow(new NullPointerException()).when(processor).process(any());

        consumer.consume(buildRecord(0, 3));

        verify(consumeRecordMapper).markFailed(eq(1L), anyInt(), anyInt(), any(), any(LocalDateTime.class));
    }

    @Test
    void consume_truncatesVeryLongExceptionMessage() throws Exception {
        when(consumeRecordMapper.tryAcquire(1L, "test-node")).thenReturn(1);
        when(registry.getProcessor("order", "order")).thenReturn(processor);
        StringBuilder sb = new StringBuilder(1000);
        for (int i = 0; i < 1000; i++) sb.append('x');
        doThrow(new RuntimeException(sb.toString())).when(processor).process(any());

        consumer.consume(buildRecord(0, 3));

        verify(consumeRecordMapper).markFailed(eq(1L), anyInt(), anyInt(), any(), any(LocalDateTime.class));
    }
}
