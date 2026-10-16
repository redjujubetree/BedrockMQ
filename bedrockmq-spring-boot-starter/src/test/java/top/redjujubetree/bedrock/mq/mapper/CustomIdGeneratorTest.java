package top.redjujubetree.bedrock.mq.mapper;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import top.redjujubetree.bedrock.mq.dialect.MySqlDialect;
import top.redjujubetree.bedrock.mq.dialect.SqlDialect;
import top.redjujubetree.bedrock.mq.dialect.SqliteDialect;
import top.redjujubetree.bedrock.mq.entity.BedrockConsumeRecord;
import top.redjujubetree.bedrock.mq.entity.BedrockMessage;
import top.redjujubetree.bedrock.mq.entity.BedrockSubscription;
import top.redjujubetree.bedrock.mq.id.BedrockIdGenerator;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CustomIdGeneratorTest {

    @Test
    void sqliteSupportsCustomIdsAndPreservesExistingSubscription() throws Exception {
        DriverManagerDataSource source = new DriverManagerDataSource(
                "jdbc:sqlite:file:bedrock-custom-id-test?mode=memory&cache=shared");
        source.setDriverClassName("org.sqlite.JDBC");
        SingleConnectionDataSource connection = new SingleConnectionDataSource(source.getConnection(), true);
        try {
            new ResourceDatabasePopulator(new ClassPathResource("schema-sqlite.sql")).execute(connection);
            verifyCustomIds(new NamedParameterJdbcTemplate(connection), new SqliteDialect());
        } finally {
            connection.destroy();
        }
    }

    @Test
    void mysqlModeSupportsCustomIdsAndRejectsPrimaryKeyCollision() {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:custom-id-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        JdbcTemplate jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE bedrock_message (id BIGINT AUTO_INCREMENT PRIMARY KEY, " +
                "topic VARCHAR(64), message_source VARCHAR(64), payload TEXT, created_at DATETIME, updated_at DATETIME)");
        jdbc.execute("CREATE TABLE bedrock_subscription (id BIGINT AUTO_INCREMENT PRIMARY KEY, " +
                "topic VARCHAR(64), consumer VARCHAR(64), max_retry INT, status INT, " +
                "created_at DATETIME, updated_at DATETIME, UNIQUE(topic, consumer))");
        jdbc.execute("CREATE TABLE bedrock_consume_record (id BIGINT AUTO_INCREMENT PRIMARY KEY, " +
                "message_id BIGINT, topic VARCHAR(64), consumer VARCHAR(64), status INT, retry_count INT, " +
                "max_retry INT, scheduled_at DATETIME, deleted INT, created_at DATETIME, updated_at DATETIME)");
        verifyCustomIds(new NamedParameterJdbcTemplate(source), new MySqlDialect());
    }

    private void verifyCustomIds(NamedParameterJdbcTemplate jdbc, SqlDialect dialect) {
        AtomicLong sequence = new AtomicLong(1000);
        List<Class<?>> entityTypes = new ArrayList<>();
        BedrockIdGenerator generator = entity -> {
            entityTypes.add(entity.getClass());
            if (entity instanceof BedrockSubscription) {
                assertThat(((BedrockSubscription) entity).getTopic()).isEqualTo("orders");
                assertThat(((BedrockSubscription) entity).getId()).isNull();
                return Integer.valueOf((int) sequence.incrementAndGet());
            } else if (entity instanceof BedrockMessage) {
                assertThat(((BedrockMessage) entity).getPayload()).isEqualTo("{}");
                assertThat(((BedrockMessage) entity).getId()).isNull();
            } else if (entity instanceof BedrockConsumeRecord) {
                assertThat(((BedrockConsumeRecord) entity).getMessageId()).isNotNull();
                assertThat(((BedrockConsumeRecord) entity).getId()).isNull();
            }
            return sequence.incrementAndGet();
        };
        BedrockMessageMapper messages = new BedrockMessageMapper(jdbc, generator);
        BedrockConsumeRecordMapper records = new BedrockConsumeRecordMapper(jdbc, generator);
        BedrockSubscriptionMapper subscriptions = new BedrockSubscriptionMapper(jdbc, dialect, generator);

        subscriptions.upsert("orders", "billing", 3);
        Long subscriptionId = subscriptions.findEnabledByTopic("orders").get(0).getId();
        subscriptions.upsert("orders", "billing", 9);
        assertThat(subscriptions.findEnabledByTopic("orders").get(0).getId()).isEqualTo(subscriptionId);
        assertThat(subscriptions.findEnabledByTopic("orders").get(0).getMaxRetry()).isEqualTo(3);

        LocalDateTime now = LocalDateTime.now().withNano(0);
        BedrockMessage message = new BedrockMessage();
        message.setTopic("orders");
        message.setMessageSource("test");
        message.setPayload("{}");
        message.setCreatedAt(now);
        message.setUpdatedAt(now);
        messages.insert(message);
        assertThat(messages.selectById(message.getId())).isNotNull();

        BedrockConsumeRecord first = record(message.getId(), "billing", now);
        BedrockConsumeRecord second = record(message.getId(), "audit", now);
        assertThat(records.insertBatch(Arrays.asList(first, second))).isEqualTo(2);
        assertThat(records.selectById(first.getId()).getMessageId()).isEqualTo(message.getId());
        assertThat(records.selectById(second.getId()).getMessageId()).isEqualTo(message.getId());
        assertThat(first.getId()).isNotEqualTo(second.getId());
        assertThat(entityTypes).contains(BedrockSubscription.class, BedrockMessage.class,
                BedrockConsumeRecord.class);

        BedrockIdGenerator collision = entity -> subscriptionId;
        BedrockSubscriptionMapper conflicting = new BedrockSubscriptionMapper(jdbc, dialect, collision);
        assertThatThrownBy(() -> conflicting.upsert("other", "consumer", 3))
                .isInstanceOf(DataAccessException.class);
        assertThat(subscriptions.findEnabledByTopic("other")).isEmpty();
    }

    private BedrockConsumeRecord record(Long messageId, String consumer, LocalDateTime now) {
        BedrockConsumeRecord record = new BedrockConsumeRecord();
        record.setMessageId(messageId);
        record.setTopic("orders");
        record.setConsumer(consumer);
        record.setStatus(0);
        record.setRetryCount(0);
        record.setMaxRetry(3);
        record.setScheduledAt(now);
        record.setCreatedAt(now);
        record.setUpdatedAt(now);
        return record;
    }
}
