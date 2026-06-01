package top.redjujubetree.bedrock.mq.consumer;

import top.redjujubetree.bedrock.mq.config.BedrockMqProperties;
import top.redjujubetree.bedrock.mq.entity.BedrockConsumeRecord;
import top.redjujubetree.bedrock.mq.mapper.BedrockConsumeRecordMapper;
import top.redjujubetree.bedrock.mq.processor.ProcessorRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.ExecutorService;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PerTypePollingManagerTest {

    @Mock BedrockConsumeRecordMapper consumeRecordMapper;
    @Mock MessageConsumer consumer;
    @Mock ProcessorRegistry registry;
    @Mock BedrockMqProperties properties;
    @Mock ExecutorService workerPool;

    PerTypePollingManager manager;

    @BeforeEach
    void setUp() {
        when(properties.getBatchSize()).thenReturn(10);
        manager = new PerTypePollingManager(consumeRecordMapper, consumer, registry, properties);
    }

    @Test
    void poll_doesNothingWhenNoPendingRecords() {
        when(consumeRecordMapper.selectPending("order", "order", 10))
                .thenReturn(Collections.emptyList());

        manager.poll("order", "order", workerPool);

        verify(workerPool, never()).submit(any(Runnable.class));
    }

    @Test
    void poll_submitsOneTaskPerPendingRecord() {
        BedrockConsumeRecord r1 = record("order", "order");
        BedrockConsumeRecord r2 = record("order", "order");
        when(consumeRecordMapper.selectPending("order", "order", 10))
                .thenReturn(Arrays.asList(r1, r2));

        manager.poll("order", "order", workerPool);

        verify(workerPool, times(2)).submit(any(Runnable.class));
    }

    @Test
    void poll_swallowsExceptionAndDoesNotPropagate() {
        when(consumeRecordMapper.selectPending("order", "order", 10))
                .thenThrow(new RuntimeException("db error"));

        manager.poll("order", "order", workerPool);

        verify(workerPool, never()).submit(any(Runnable.class));
    }

    @Test
    void poll_isIndependentPerTopicConsumerPair() {
        BedrockConsumeRecord orderRecord = record("order", "order");
        BedrockConsumeRecord inventoryRecord = record("order-created", "inventory");
        when(consumeRecordMapper.selectPending("order", "order", 10))
                .thenReturn(Collections.singletonList(orderRecord));
        when(consumeRecordMapper.selectPending("order-created", "inventory", 10))
                .thenReturn(Collections.singletonList(inventoryRecord));

        ExecutorService inventoryPool = mock(ExecutorService.class);
        manager.poll("order", "order", workerPool);
        manager.poll("order-created", "inventory", inventoryPool);

        verify(workerPool, times(1)).submit(any(Runnable.class));
        verify(inventoryPool, times(1)).submit(any(Runnable.class));
    }

    private BedrockConsumeRecord record(String topic, String consumer) {
        BedrockConsumeRecord r = new BedrockConsumeRecord();
        r.setId(1L);
        r.setMessageId(1L);
        r.setTopic(topic);
        r.setConsumer(consumer);
        return r;
    }
}
