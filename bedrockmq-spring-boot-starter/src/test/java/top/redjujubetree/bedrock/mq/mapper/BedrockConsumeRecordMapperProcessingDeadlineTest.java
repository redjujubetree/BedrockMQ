package top.redjujubetree.bedrock.mq.mapper;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import top.redjujubetree.bedrock.mq.constant.MessageStatus;
import top.redjujubetree.bedrock.mq.entity.BedrockConsumeRecord;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class BedrockConsumeRecordMapperProcessingDeadlineTest {

    private BedrockConsumeRecordMapper mapper;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL(
                "jdbc:h2:mem:processing-deadline-test;" +
                        "MODE=MySQL;" +
                        "DATABASE_TO_LOWER=TRUE;" +
                        "CASE_INSENSITIVE_IDENTIFIERS=TRUE;" +
                        "DB_CLOSE_DELAY=-1"
        );
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DROP TABLE IF EXISTS bedrock_consume_record");
        jdbc.execute("CREATE TABLE bedrock_consume_record (" +
                "id BIGINT AUTO_INCREMENT PRIMARY KEY, message_id BIGINT NOT NULL, " +
                "topic VARCHAR(64) NOT NULL, consumer VARCHAR(64) NOT NULL, status TINYINT NOT NULL, " +
                "node_id VARCHAR(128), processing_token VARCHAR(64), processing_started_at DATETIME, " +
                "processing_expires_at DATETIME, retry_count INT NOT NULL, " +
                "max_retry INT NOT NULL, error_msg VARCHAR(512), scheduled_at DATETIME NOT NULL, " +
                "deleted TINYINT NOT NULL, created_at DATETIME NOT NULL, updated_at DATETIME NOT NULL)");
        mapper = new BedrockConsumeRecordMapper(new NamedParameterJdbcTemplate(dataSource));
    }

    @Test
    void staleOwnerCannotCompleteAfterDeadlineRecoveryAndReacquire() {
        Long id = insertRecord(MessageStatus.PENDING, LocalDateTime.of(2026, 7, 13, 10, 0));
        LocalDateTime firstStart = LocalDateTime.of(2026, 7, 13, 10, 1);
        LocalDateTime firstDeadline = firstStart.plusMinutes(30);
        assertThat(mapper.tryAcquire(id, "node-a", "token-a", firstStart, firstDeadline))
                .isEqualTo(1);

        assertThat(mapper.recoverTimedOutRecords(firstDeadline)).isEqualTo(1);
        assertThat(mapper.tryAcquire(id, "node-b", "token-b", firstDeadline,
                firstDeadline.plusMinutes(30))).isEqualTo(1);

        assertThat(mapper.markCompleted(id, "token-a", firstDeadline.plusSeconds(1))).isZero();
        assertThat(mapper.markFailed(id, "token-a", MessageStatus.FAILED, 2,
                "stale failure", firstDeadline.plusSeconds(1))).isZero();
        assertThat(mapper.markCompleted(id, "token-b", firstDeadline.plusSeconds(2))).isEqualTo(1);

        BedrockConsumeRecord saved = mapper.selectById(id);
        assertThat(saved.getStatus()).isEqualTo(MessageStatus.COMPLETED);
        assertThat(saved.getProcessingToken()).isNull();
        assertThat(saved.getNodeId()).isNull();
    }

    @Test
    void recordRemainsProcessingUntilItsFixedDeadline() {
        Long id = insertRecord(MessageStatus.PENDING, LocalDateTime.of(2026, 7, 13, 10, 0));
        LocalDateTime start = LocalDateTime.of(2026, 7, 13, 10, 1);
        LocalDateTime deadline = start.plusMinutes(30);
        assertThat(mapper.tryAcquire(id, "node-a", "token-a", start, deadline)).isEqualTo(1);

        assertThat(mapper.recoverTimedOutRecords(deadline.minusNanos(1))).isZero();
        BedrockConsumeRecord processing = mapper.selectById(id);
        assertThat(processing.getStatus()).isEqualTo(MessageStatus.PROCESSING);
        assertThat(processing.getProcessingExpiresAt()).isEqualTo(deadline);

        assertThat(mapper.recoverTimedOutRecords(deadline)).isEqualTo(1);
        BedrockConsumeRecord recovered = mapper.selectById(id);
        assertThat(recovered.getStatus()).isEqualTo(MessageStatus.PENDING);
        assertThat(recovered.getProcessingExpiresAt()).isNull();
        assertThat(recovered.getRetryCount()).isEqualTo(1);
    }

    @Test
    void currentOwnerCanFailWithMatchingToken() {
        LocalDateTime start = LocalDateTime.of(2026, 7, 13, 10, 1);
        Long id = insertRecord(MessageStatus.PENDING, start.minusMinutes(1));
        assertThat(mapper.tryAcquire(id, "node-a", "token-a", start,
                start.plusMinutes(30))).isEqualTo(1);

        assertThat(mapper.markFailed(id, "token-a", MessageStatus.PENDING, 1,
                "temporary failure", start.plusSeconds(1))).isEqualTo(1);

        BedrockConsumeRecord saved = mapper.selectById(id);
        assertThat(saved.getStatus()).isEqualTo(MessageStatus.PENDING);
        assertThat(saved.getProcessingToken()).isNull();
        assertThat(saved.getProcessingExpiresAt()).isNull();
    }

    @Test
    void processingRecordWithoutDeadlineIsNotRecovered() {
        LocalDateTime oldUpdatedAt = LocalDateTime.of(2026, 7, 13, 9, 0);
        Long id = insertRecord(MessageStatus.PROCESSING, oldUpdatedAt);
        LocalDateTime now = LocalDateTime.of(2026, 7, 13, 10, 0);

        assertThat(mapper.recoverTimedOutRecords(now)).isZero();

        BedrockConsumeRecord saved = mapper.selectById(id);
        assertThat(saved.getStatus()).isEqualTo(MessageStatus.PROCESSING);
        assertThat(saved.getRetryCount()).isZero();
    }

    private Long insertRecord(int status, LocalDateTime updatedAt) {
        BedrockConsumeRecord record = new BedrockConsumeRecord();
        record.setMessageId(1L);
        record.setTopic("order");
        record.setConsumer("billing");
        record.setStatus(status);
        record.setRetryCount(0);
        record.setMaxRetry(3);
        record.setScheduledAt(updatedAt);
        record.setCreatedAt(updatedAt);
        record.setUpdatedAt(updatedAt);
        mapper.insert(record);
        return record.getId();
    }
}
