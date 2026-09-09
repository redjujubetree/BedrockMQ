package top.redjujubetree.bedrock.mq.consumer;

import top.redjujubetree.bedrock.mq.config.BedrockMqProperties;
import top.redjujubetree.bedrock.mq.entity.BedrockConsumeRecord;
import top.redjujubetree.bedrock.mq.mapper.BedrockConsumeRecordMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PerTypePollingManagerTest {

    @Mock BedrockConsumeRecordMapper consumeRecordMapper;
    @Mock
    MessageProcessor processor;
    @Mock
    ConsumerRegistry registry;
    @Mock BedrockMqProperties properties;
    @Mock ThreadPoolExecutor workerPool;

    PerTypePollingManager manager;

    @BeforeEach
    void setUp() {
        lenient().when(properties.getBatchSize()).thenReturn(10);
        lenient().when(workerPool.getMaximumPoolSize()).thenReturn(1);
        lenient().when(workerPool.getActiveCount()).thenReturn(0);
        lenient().when(workerPool.getQueue()).thenReturn(new ArrayBlockingQueue<Runnable>(9));
        manager = new PerTypePollingManager(consumeRecordMapper, processor, registry, properties);
    }

    @Test
    void poll_doesNothingWhenNoPendingRecords() {
        when(consumeRecordMapper.selectPending("order", "order", 10))
                .thenReturn(Collections.emptyList());

        manager.poll("order", "order", workerPool);

        verify(workerPool, never()).execute(any(Runnable.class));
    }

    @Test
    void poll_submitsOneTaskPerPendingRecord() {
        BedrockConsumeRecord r1 = record("order", "order");
        BedrockConsumeRecord r2 = record("order", "order");
        when(consumeRecordMapper.selectPending("order", "order", 10))
                .thenReturn(Arrays.asList(r1, r2));

        manager.poll("order", "order", workerPool);

        verify(workerPool, times(2)).execute(any(Runnable.class));
    }

    @Test
    void poll_swallowsExceptionAndDoesNotPropagate() {
        when(consumeRecordMapper.selectPending("order", "order", 10))
                .thenThrow(new RuntimeException("db error"));

        manager.poll("order", "order", workerPool);

        verify(workerPool, never()).execute(any(Runnable.class));
    }

    @Test
    void poll_isIndependentPerTopicConsumerPair() {
        BedrockConsumeRecord orderRecord = record("order", "order");
        BedrockConsumeRecord inventoryRecord = record("order-created", "inventory");
        when(consumeRecordMapper.selectPending("order", "order", 10))
                .thenReturn(Collections.singletonList(orderRecord));
        when(consumeRecordMapper.selectPending("order-created", "inventory", 10))
                .thenReturn(Collections.singletonList(inventoryRecord));

        ThreadPoolExecutor inventoryPool = mock(ThreadPoolExecutor.class);
        when(inventoryPool.getMaximumPoolSize()).thenReturn(1);
        when(inventoryPool.getQueue()).thenReturn(new ArrayBlockingQueue<Runnable>(9));
        manager.poll("order", "order", workerPool);
        manager.poll("order-created", "inventory", inventoryPool);

        verify(workerPool, times(1)).execute(any(Runnable.class));
        verify(inventoryPool, times(1)).execute(any(Runnable.class));
    }

    @Test
    void createWorkerPool_usesBatchSizedBoundedQueueAndAbortPolicy() {
        when(properties.getBatchSize()).thenReturn(7);

        ThreadPoolExecutor executor = (ThreadPoolExecutor)
                manager.createWorkerPool("order", "billing", 3);
        try {
            assertThat(executor.getCorePoolSize()).isEqualTo(3);
            assertThat(executor.getMaximumPoolSize()).isEqualTo(3);
            assertThat(executor.getQueue()).isInstanceOf(ArrayBlockingQueue.class);
            assertThat(executor.getQueue().remainingCapacity()).isEqualTo(7);
            assertThat(executor.getRejectedExecutionHandler())
                    .isInstanceOf(ThreadPoolExecutor.AbortPolicy.class);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void createPollingScheduler_usesOneSharedMultiThreadExecutor() {
        ScheduledThreadPoolExecutor scheduler = (ScheduledThreadPoolExecutor)
                manager.createPollingScheduler();
        try {
            assertThat(scheduler.getCorePoolSize()).isEqualTo(2);
        } finally {
            scheduler.shutdownNow();
        }
    }

    @Test
    void afterPropertiesSet_createsOneSharedSchedulerForAllRegisteredPairs() {
        when(registry.getRegisteredKeys()).thenReturn(new LinkedHashSet<>(Arrays.asList(
                "order:billing", "order:inventory")));
        when(properties.getConcurrencyFor(anyString())).thenReturn(1);
        when(properties.getPollIntervalMs()).thenReturn(3_600_000L);
        AtomicInteger schedulerCreations = new AtomicInteger();
        manager = new PerTypePollingManager(
                consumeRecordMapper, processor, registry, properties) {
            @Override
            ScheduledExecutorService createPollingScheduler() {
                schedulerCreations.incrementAndGet();
                return super.createPollingScheduler();
            }
        };

        try {
            manager.afterPropertiesSet();

            assertThat(schedulerCreations.get()).isEqualTo(1);
        } finally {
            manager.destroy();
        }
    }

    @Test
    void saturatedWorkerPool_rejectsOverflowInsteadOfRunningOnCallingThread() throws Exception {
        when(properties.getBatchSize()).thenReturn(1);
        ThreadPoolExecutor executor = (ThreadPoolExecutor)
                manager.createWorkerPool("order", "billing", 1);
        CountDownLatch workerStarted = new CountDownLatch(1);
        CountDownLatch releaseTasks = new CountDownLatch(1);
        try {
            executor.execute(() -> {
                workerStarted.countDown();
                await(releaseTasks);
            });
            assertThat(workerStarted.await(1, TimeUnit.SECONDS)).isTrue();

            executor.execute(() -> await(releaseTasks));

            assertThatThrownBy(() -> executor.execute(() -> { }))
                    .isInstanceOf(RejectedExecutionException.class);
        } finally {
            releaseTasks.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void poll_skipsDatabaseQueryWhenWorkerPoolIsFull() {
        BlockingQueue<Runnable> fullQueue = new ArrayBlockingQueue<>(1);
        fullQueue.add(() -> { });
        when(workerPool.getActiveCount()).thenReturn(1);
        when(workerPool.getQueue()).thenReturn(fullQueue);

        manager.poll("order", "order", workerPool);

        verify(consumeRecordMapper, never())
                .selectPending(anyString(), anyString(), anyInt());
        verify(workerPool, never()).execute(any(Runnable.class));
    }

    @Test
    void poll_limitsQueryToAvailableWorkerAndQueueCapacity() {
        when(workerPool.getMaximumPoolSize()).thenReturn(3);
        when(workerPool.getActiveCount()).thenReturn(2);
        when(workerPool.getQueue()).thenReturn(new ArrayBlockingQueue<Runnable>(1));
        when(consumeRecordMapper.selectPending("order", "order", 2))
                .thenReturn(Collections.emptyList());

        manager.poll("order", "order", workerPool);

        verify(consumeRecordMapper).selectPending("order", "order", 2);
    }

    @Test
    void poll_stopsSubmittingWhenWorkerRejectsAndLeavesRemainingRecordsPending() {
        BedrockConsumeRecord r1 = record("order", "order");
        BedrockConsumeRecord r2 = record("order", "order");
        when(consumeRecordMapper.selectPending("order", "order", 10))
                .thenReturn(Arrays.asList(r1, r2));
        doThrow(new RejectedExecutionException("full"))
                .when(workerPool).execute(any(Runnable.class));

        manager.poll("order", "order", workerPool);

        verify(workerPool, times(1)).execute(any(Runnable.class));
        verifyNoInteractions(processor);
    }

    private void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
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
