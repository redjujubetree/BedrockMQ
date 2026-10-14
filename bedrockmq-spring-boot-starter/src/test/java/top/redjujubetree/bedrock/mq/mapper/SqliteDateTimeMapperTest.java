package top.redjujubetree.bedrock.mq.mapper;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import top.redjujubetree.bedrock.mq.constant.MessageStatus;
import top.redjujubetree.bedrock.mq.dialect.SqliteDialect;
import top.redjujubetree.bedrock.mq.entity.BedrockConsumeRecord;
import top.redjujubetree.bedrock.mq.entity.BedrockMessage;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class SqliteDateTimeMapperTest {

    @Test
    void sqliteSupportsPublishingPollingAndFixedDeadlineRecovery() throws Exception {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:sqlite:file:bedrock-time-test?mode=memory&cache=shared");
        dataSource.setDriverClassName("org.sqlite.JDBC");
        // Keep one connection open so the shared in-memory database survives between mapper calls.
        org.springframework.jdbc.datasource.SingleConnectionDataSource singleConnection =
                new org.springframework.jdbc.datasource.SingleConnectionDataSource(dataSource.getConnection(), true);
        try {
            new ResourceDatabasePopulator(new ClassPathResource("schema-sqlite.sql"))
                    .execute(singleConnection);
            NamedParameterJdbcTemplate namedJdbc = new NamedParameterJdbcTemplate(singleConnection);
            BedrockMessageMapper messages = new BedrockMessageMapper(namedJdbc);
            BedrockConsumeRecordMapper records = new BedrockConsumeRecordMapper(namedJdbc);
            BedrockSubscriptionMapper subscriptions =
                    new BedrockSubscriptionMapper(namedJdbc, new SqliteDialect());

            subscriptions.upsert("orders", "billing", 3);
            assertThat(subscriptions.findEnabledByTopic("orders")).hasSize(1);

            LocalDateTime start = LocalDateTime.now().minusMinutes(1).withNano(0);
            BedrockMessage message = new BedrockMessage();
            message.setTopic("orders");
            message.setMessageSource("test");
            message.setPayload("{}");
            message.setCreatedAt(start);
            message.setUpdatedAt(start);
            messages.insert(message);
            assertThat(messages.selectById(message.getId()).getCreatedAt()).isEqualTo(start);

            BedrockConsumeRecord record = new BedrockConsumeRecord();
            record.setMessageId(message.getId());
            record.setTopic("orders");
            record.setConsumer("billing");
            record.setStatus(MessageStatus.PENDING);
            record.setRetryCount(0);
            record.setMaxRetry(3);
            record.setScheduledAt(start);
            record.setCreatedAt(start);
            record.setUpdatedAt(start);
            records.insert(record);
            assertThat(records.selectPending("orders", "billing", 1)).hasSize(1);

            LocalDateTime deadline = start.plusMinutes(2);
            assertThat(records.tryAcquire(record.getId(), "node", "token", start, deadline)).isEqualTo(1);
            assertThat(records.selectById(record.getId()).getProcessingExpiresAt()).isEqualTo(deadline);
            assertThat(records.recoverTimeoutRecords(deadline.minusSeconds(1))).isZero();
            assertThat(records.recoverTimeoutRecords(deadline)).isEqualTo(1);
            assertThat(records.selectById(record.getId()).getStatus()).isEqualTo(MessageStatus.PENDING);
        } finally {
            singleConnection.destroy();
        }
    }
}
