package top.redjujubetree.bedrock.mq.consumer;

import top.redjujubetree.bedrock.mq.config.BedrockMqProperties;
import top.redjujubetree.bedrock.mq.entity.BedrockConsumeRecord;
import top.redjujubetree.bedrock.mq.mapper.BedrockConsumeRecordMapper;
import top.redjujubetree.bedrock.mq.processor.ProcessorRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

public class PerTypePollingManager implements InitializingBean, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(PerTypePollingManager.class);

    private final BedrockConsumeRecordMapper consumeRecordMapper;
    private final MessageConsumer consumer;
    private final ProcessorRegistry registry;
    private final BedrockMqProperties properties;

    private final List<ScheduledExecutorService> schedulers = new ArrayList<>();
    private final List<ExecutorService> workerPools = new ArrayList<>();

    public PerTypePollingManager(BedrockConsumeRecordMapper consumeRecordMapper,
                                 MessageConsumer consumer,
                                 ProcessorRegistry registry,
                                 BedrockMqProperties properties) {
        this.consumeRecordMapper = consumeRecordMapper;
        this.consumer = consumer;
        this.registry = registry;
        this.properties = properties;
    }

    @Override
    public void afterPropertiesSet() {
        for (String registryKey : registry.getRegisteredKeys()) {
            String[] parts = ProcessorRegistry.splitKey(registryKey);
            String topic = parts[0];
            String consumerName = parts[1];
            int concurrency = properties.getConcurrencyFor(registryKey);

            ExecutorService workerPool = Executors.newFixedThreadPool(concurrency, r -> {
                Thread t = new Thread(r, "bedrock-" + topic + "-" + consumerName + "-worker");
                t.setDaemon(true);
                return t;
            });
            ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "bedrock-" + topic + "-" + consumerName + "-scheduler");
                t.setDaemon(true);
                return t;
            });

            scheduler.scheduleWithFixedDelay(
                    () -> poll(topic, consumerName, workerPool),
                    0, properties.getPollIntervalMs(), TimeUnit.MILLISECONDS);

            schedulers.add(scheduler);
            workerPools.add(workerPool);

            log.info("Started polling for topic={} consumer={} concurrency={}", topic, consumerName, concurrency);
        }
    }

    void poll(String topic, String consumerName, ExecutorService workerPool) {
        try {
            List<BedrockConsumeRecord> records =
                    consumeRecordMapper.selectPending(topic, consumerName, properties.getBatchSize());
            for (BedrockConsumeRecord record : records) {
                workerPool.submit(() -> consumer.consume(record));
            }
        } catch (Exception e) {
            log.warn("Polling failed for topic={} consumer={}, will retry: {}", topic, consumerName, e.getMessage());
        }
    }

    @Override
    public void destroy() {
        schedulers.forEach(ExecutorService::shutdown);
        workerPools.forEach(ExecutorService::shutdown);
        schedulers.forEach(this::awaitTermination);
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
