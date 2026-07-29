package top.redjujubetree.bedrock.mq.recovery;

import top.redjujubetree.bedrock.mq.mapper.BedrockConsumeRecordMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.time.LocalDateTime;

public class TimeoutRecoveryTask implements InitializingBean, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(TimeoutRecoveryTask.class);

    private final BedrockConsumeRecordMapper consumeRecordMapper;

    private ScheduledExecutorService scheduler;

    public TimeoutRecoveryTask(BedrockConsumeRecordMapper consumeRecordMapper) {
        this.consumeRecordMapper = consumeRecordMapper;
    }

    @Override
    public void afterPropertiesSet() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "bedrock-timeout-recovery");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(this::recoverSafely, 60, 60, TimeUnit.SECONDS);
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
        LocalDateTime now = LocalDateTime.now();
        int count = consumeRecordMapper.recoverTimedOutRecords(now);
        if (count > 0) {
            log.warn("Recovered {} records that exceeded their processing deadline", count);
        }
    }

    void recoverSafely() {
        try {
            recover();
        } catch (Exception e) {
            log.error("Timeout recovery failed; will retry next cycle", e);
        }
    }
}
