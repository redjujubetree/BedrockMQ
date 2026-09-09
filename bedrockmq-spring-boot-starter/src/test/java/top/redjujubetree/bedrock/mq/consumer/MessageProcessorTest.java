package top.redjujubetree.bedrock.mq.consumer;

import top.redjujubetree.bedrock.mq.config.BedrockMqProperties;
import top.redjujubetree.bedrock.mq.constant.MessageStatus;
import top.redjujubetree.bedrock.mq.entity.BedrockConsumeRecord;
import top.redjujubetree.bedrock.mq.entity.BedrockMessage;
import top.redjujubetree.bedrock.mq.mapper.BedrockConsumeRecordMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MessageProcessorTest {

    @Mock BedrockConsumeRecordMapper consumeRecordMapper;
    @Mock
    ConsumerRegistry registry;
    @Mock BedrockMqProperties properties;
    @Mock
    MessageConsumer consumer;
    MessageProcessor processor;

    @BeforeEach
    void setUp() {
        lenient().when(properties.getNodeId()).thenReturn("test-node");
        lenient().when(properties.getProcessingTimeoutMinutes()).thenReturn(15);
        lenient().when(consumeRecordMapper.markCompleted(anyLong(), anyString(), any())).thenReturn(1);
        lenient().when(consumeRecordMapper.markFailed(anyLong(), anyString(), anyString(), any())).thenReturn(1);
        processor = new MessageProcessor(consumeRecordMapper, registry, properties);
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
    void process_skipsAllProcessingWhenCasAcquireFails() {
        when(registry.getConsumer("order", "order")).thenReturn(consumer);
        when(consumeRecordMapper.tryAcquire(eq(1L), eq("test-node"), anyString(), any(), any())).thenReturn(0);

        processor.process(buildRecord(0, 3));

        verify(consumeRecordMapper, never()).markCompleted(anyLong(), anyString(), any());
    }

    @Test
    void process_skipsAcquireWhenNoConsumerRegistered() {
        when(registry.getConsumer("order", "order")).thenReturn(null);

        processor.process(buildRecord(0, 3));

        verify(consumeRecordMapper, never()).tryAcquire(anyLong(), anyString(), anyString(), any(), any());
        verify(consumeRecordMapper, never()).markCompleted(anyLong(), anyString(), any());
    }

    @Test
    void process_marksCompletedAndClearsErrorMsgOnSuccess() throws Exception {
        when(consumeRecordMapper.tryAcquire(eq(1L), eq("test-node"), anyString(), any(), any())).thenReturn(1);
        when(registry.getConsumer("order", "order")).thenReturn(consumer);

        processor.process(buildRecord(0, 3));

        verify(consumer).consume(any(BedrockMessage.class));
        verify(consumeRecordMapper).markCompleted(eq(1L), anyString(), any(LocalDateTime.class));
    }

    @Test
    void process_setsFixedExpiryFromProcessingTimeout() throws Exception {
        when(consumeRecordMapper.tryAcquire(eq(1L), eq("test-node"), anyString(), any(), any()))
                .thenReturn(1);
        when(registry.getConsumer("order", "order")).thenReturn(consumer);

        processor.process(buildRecord(0, 3));

        org.mockito.ArgumentCaptor<LocalDateTime> startedAt =
                org.mockito.ArgumentCaptor.forClass(LocalDateTime.class);
        org.mockito.ArgumentCaptor<LocalDateTime> deadlineAt =
                org.mockito.ArgumentCaptor.forClass(LocalDateTime.class);
        verify(consumeRecordMapper).tryAcquire(
                eq(1L), eq("test-node"), anyString(), startedAt.capture(), deadlineAt.capture());
        assertThat(ChronoUnit.MINUTES.between(startedAt.getValue(), deadlineAt.getValue()))
                .isEqualTo(15);
    }

    @Test
    void constructor_rejectsNonPositiveProcessingTimeout() {
        when(properties.getProcessingTimeoutMinutes()).thenReturn(0);

        assertThatThrownBy(() -> new MessageProcessor(consumeRecordMapper, registry, properties))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("processing-timeout-minutes");
    }

    @Test
    void process_resetsStatusToPendingWhenRetriesAreRemaining() throws Exception {
        when(consumeRecordMapper.tryAcquire(eq(1L), eq("test-node"), anyString(), any(), any())).thenReturn(1);
        when(registry.getConsumer("order", "order")).thenReturn(consumer);
        doThrow(new RuntimeException("db timeout")).when(consumer).consume(any());

        // retryCount=0, maxRetry=3 → nextRetry(1) < maxRetry(3) → back to PENDING
        processor.process(buildRecord(0, 3));

        verify(consumeRecordMapper).markFailed(eq(1L), anyString(), any(), any(LocalDateTime.class));
    }

    @Test
    void process_marksFailedWhenMaxRetriesAreExhausted() throws Exception {
        when(consumeRecordMapper.tryAcquire(eq(1L), eq("test-node"), anyString(), any(), any())).thenReturn(1);
        when(registry.getConsumer("order", "order")).thenReturn(consumer);
        doThrow(new RuntimeException("db timeout")).when(consumer).consume(any());

        // retryCount=2, maxRetry=3 → nextRetry(3) >= maxRetry(3) → FAILED
        processor.process(buildRecord(2, 3));

        verify(consumeRecordMapper).markFailed(eq(1L), anyString(), any(), any(LocalDateTime.class));
    }

    @Test
    void process_handlesNullPointerExceptionWithNoMessageByFallingBackToStackTrace() throws Exception {
        when(consumeRecordMapper.tryAcquire(eq(1L), eq("test-node"), anyString(), any(), any())).thenReturn(1);
        when(registry.getConsumer("order", "order")).thenReturn(consumer);
        doThrow(new NullPointerException()).when(consumer).consume(any());

        processor.process(buildRecord(0, 3));

        verify(consumeRecordMapper).markFailed(eq(1L), anyString(), any(), any(LocalDateTime.class));
    }

    @Test
    void process_marksFailedImmediatelyWhenMaxRetryIsOne() throws Exception {
        when(consumeRecordMapper.tryAcquire(eq(1L), eq("test-node"), anyString(), any(), any())).thenReturn(1);
        when(registry.getConsumer("order", "order")).thenReturn(consumer);
        doThrow(new RuntimeException("fail")).when(consumer).consume(any());

        // retryCount=0, maxRetry=1 → nextRetry(1) >= maxRetry(1) → FAILED on first attempt
        processor.process(buildRecord(0, 1));

        verify(consumeRecordMapper).markFailed(eq(1L), anyString(), any(), any(LocalDateTime.class));
    }

    @Test
    void process_truncatesVeryLongExceptionMessage() throws Exception {
        when(consumeRecordMapper.tryAcquire(eq(1L), eq("test-node"), anyString(), any(), any())).thenReturn(1);
        when(registry.getConsumer("order", "order")).thenReturn(consumer);
        StringBuilder sb = new StringBuilder(1000);
        for (int i = 0; i < 1000; i++) sb.append('x');
        doThrow(new RuntimeException(sb.toString())).when(consumer).consume(any());

        processor.process(buildRecord(0, 3));

        verify(consumeRecordMapper).markFailed(eq(1L), anyString(), any(), any(LocalDateTime.class));
    }
}
