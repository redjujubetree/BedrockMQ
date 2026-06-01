package top.redjujubetree.bedrock.mq.recovery;

import top.redjujubetree.bedrock.mq.config.BedrockMqProperties;
import top.redjujubetree.bedrock.mq.mapper.BedrockConsumeRecordMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class TimeoutRecoveryTask implements InitializingBean, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(TimeoutRecoveryTask.class);

    private final BedrockConsumeRecordMapper consumeRecordMapper;
    private final BedrockMqProperties properties;

    private ScheduledExecutorService scheduler;

    public TimeoutRecoveryTask(BedrockConsumeRecordMapper consumeRecordMapper, BedrockMqProperties properties) {
        this.consumeRecordMapper = consumeRecordMapper;
        this.properties = properties;
    }

    @Override
    public void afterPropertiesSet() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "bedrock-timeout-recovery");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(this::recover, 60, 60, TimeUnit.SECONDS);
    }

    @Override
    public void destroy() {
        if (scheduler != null) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    void recover() {
        int count = consumeRecordMapper.recoverTimeoutRecords(properties.getProcessingTimeoutMinutes());
        if (count > 0) {
            log.warn("Recovered {} timeout consume records (timeout={}min)", count, properties.getProcessingTimeoutMinutes());
        }
    }
}
