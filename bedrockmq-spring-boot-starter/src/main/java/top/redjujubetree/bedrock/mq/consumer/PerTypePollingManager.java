package top.redjujubetree.bedrock.mq.consumer;

import top.redjujubetree.bedrock.mq.config.BedrockMqProperties;
import top.redjujubetree.bedrock.mq.entity.BedrockConsumeRecord;
import top.redjujubetree.bedrock.mq.mapper.BedrockConsumeRecordMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class PerTypePollingManager implements InitializingBean, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(PerTypePollingManager.class);
    private static final int POLLING_SCHEDULER_THREADS = 2;

    private final BedrockConsumeRecordMapper consumeRecordMapper;
    private final MessageProcessor processor;
    private final ConsumerRegistry registry;
    private final BedrockMqProperties properties;

    private final List<ThreadPoolExecutor> workerPools = new ArrayList<>();

    private ScheduledExecutorService scheduler;

    public PerTypePollingManager(BedrockConsumeRecordMapper consumeRecordMapper,
                                 MessageProcessor processor,
                                 ConsumerRegistry registry,
                                 BedrockMqProperties properties) {
        this.consumeRecordMapper = consumeRecordMapper;
        this.processor = processor;
        this.registry = registry;
        this.properties = properties;
    }

    @Override
    public void afterPropertiesSet() {
        Set<String> registeredKeys = registry.getRegisteredKeys();
        if (registeredKeys.isEmpty()) {
            return;
        }

        scheduler = createPollingScheduler();
        for (String registryKey : registeredKeys) {
            String[] parts = ConsumerRegistry.splitKey(registryKey);
            String topic = parts[0];
            String consumerName = parts[1];
            int concurrency = properties.getConcurrencyFor(registryKey);

            ThreadPoolExecutor workerPool = createWorkerPool(topic, consumerName, concurrency);

            scheduler.scheduleWithFixedDelay(
                    () -> poll(topic, consumerName, workerPool),
                    0, properties.getPollIntervalMs(), TimeUnit.MILLISECONDS);

            workerPools.add(workerPool);

            log.info("Started polling for topic={} consumer={} concurrency={}", topic, consumerName, concurrency);
        }
        log.info("Started shared polling scheduler threads={} registrations={}",
                POLLING_SCHEDULER_THREADS, registeredKeys.size());
    }

    ScheduledExecutorService createPollingScheduler() {
        AtomicInteger threadNumber = new AtomicInteger();
        return Executors.newScheduledThreadPool(POLLING_SCHEDULER_THREADS, r -> {
            Thread t = new Thread(r,
                    "bedrock-polling-scheduler-" + threadNumber.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
    }

    ThreadPoolExecutor createWorkerPool(String topic, String consumerName, int concurrency) {
        return new ThreadPoolExecutor(
                concurrency,
                concurrency,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<Runnable>(properties.getBatchSize()),
                r -> {
                    Thread t = new Thread(r, "bedrock-" + topic + "-" + consumerName + "-worker");
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    void poll(String topic, String consumerName, ThreadPoolExecutor workerPool) {
        try {
            int availableCapacity = availableCapacity(workerPool);
            if (availableCapacity == 0) {
                return;
            }

            int limit = Math.min(properties.getBatchSize(), availableCapacity);
            List<BedrockConsumeRecord> records =
                    consumeRecordMapper.selectPending(topic, consumerName, limit);
            for (BedrockConsumeRecord record : records) {
                try {
                    workerPool.execute(() -> processor.process(record));
                } catch (RejectedExecutionException e) {
                    // CAS acquisition happens inside the task, so this and all remaining
                    // records are still PENDING and can be fetched by a later poll.
                    log.debug("Worker pool is full; stopped submitting this poll: " +
                                    "topic={} consumer={} recordId={}",
                            topic, consumerName, record.getId());
                    break;
                }
            }
        } catch (Exception e) {
            log.warn("Polling failed for topic={} consumer={}, will retry: {}", topic, consumerName, e.getMessage());
        }
    }

    int availableCapacity(ThreadPoolExecutor workerPool) {
        if (workerPool.isShutdown()) {
            return 0;
        }
        int idleWorkers = Math.max(0,
                workerPool.getMaximumPoolSize() - workerPool.getActiveCount());
        long capacity = (long) idleWorkers + workerPool.getQueue().remainingCapacity();
        return capacity > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) capacity;
    }

    @Override
    public void destroy() {
        if (scheduler != null) {
            scheduler.shutdown();
            awaitTermination(scheduler);
        }
        workerPools.forEach(ExecutorService::shutdown);
        workerPools.forEach(this::awaitTermination);
    }

    private void awaitTermination(ExecutorService executor) {
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
