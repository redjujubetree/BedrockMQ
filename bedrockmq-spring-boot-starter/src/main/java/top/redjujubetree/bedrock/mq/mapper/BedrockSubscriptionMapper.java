package top.redjujubetree.bedrock.mq.mapper;

import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.dao.DuplicateKeyException;
import top.redjujubetree.bedrock.mq.dialect.SqlDialect;
import top.redjujubetree.bedrock.mq.dialect.SqliteDialect;
import top.redjujubetree.bedrock.mq.entity.BedrockSubscription;
import top.redjujubetree.bedrock.mq.id.BedrockIdGenerator;

import java.time.LocalDateTime;
import java.util.List;

public class BedrockSubscriptionMapper {

    private static final RowMapper<BedrockSubscription> ROW_MAPPER =
            BeanPropertyRowMapper.newInstance(BedrockSubscription.class);

    private final NamedParameterJdbcTemplate jdbc;
    private final SqlDialect dialect;
    private final BedrockIdGenerator idGenerator;

    public BedrockSubscriptionMapper(NamedParameterJdbcTemplate jdbc, SqlDialect dialect) {
        this(jdbc, dialect, null);
    }

    public BedrockSubscriptionMapper(NamedParameterJdbcTemplate jdbc, SqlDialect dialect,
                                     BedrockIdGenerator idGenerator) {
        this.jdbc = jdbc;
        this.dialect = dialect;
        this.idGenerator = idGenerator;
    }

    /** Inserts a subscription when absent; existing status and max_retry are preserved. */
    public void upsert(String topic, String consumer, int maxRetry) {
        LocalDateTime now = LocalDateTime.now();
        JdbcDateTimeParameterSource params = new JdbcDateTimeParameterSource()
                .addValue("topic", topic)
                .addValue("consumer", consumer)
                .addValue("maxRetry", maxRetry)
                .addValue("now", now);
        if (idGenerator == null) {
            jdbc.update(dialect.upsertSubscriptionSql(), params);
            return;
        }
        BedrockSubscription subscription = new BedrockSubscription();
        subscription.setTopic(topic);
        subscription.setConsumer(consumer);
        subscription.setMaxRetry(maxRetry);
        subscription.setStatus(1);
        subscription.setCreatedAt(now);
        subscription.setUpdatedAt(now);
        long id = idGenerator.nextId(subscription).longValue();
        params.addValue("id", id);
        if (dialect instanceof SqliteDialect) {
            jdbc.update(dialect.insertSubscriptionWithIdSql(), params);
            return;
        }
        try {
            jdbc.update(dialect.insertSubscriptionWithIdSql(), params);
        } catch (DuplicateKeyException e) {
            List<BedrockSubscription> idMatches = jdbc.query(
                    "SELECT * FROM bedrock_subscription WHERE id = :id", params, ROW_MAPPER);
            if (!idMatches.isEmpty() &&
                    (!topic.equals(idMatches.get(0).getTopic()) ||
                            !consumer.equals(idMatches.get(0).getConsumer()))) {
                throw e;
            }
            List<BedrockSubscription> existing = jdbc.query(
                    "SELECT * FROM bedrock_subscription WHERE topic = :topic AND consumer = :consumer",
                    params, ROW_MAPPER);
            if (existing.isEmpty()) {
                throw e;
            }
        }
    }

    /** Returns only enabled (status=1) subscriptions for the given topic. */
    public List<BedrockSubscription> findEnabledByTopic(String topic) {
        String sql = "SELECT * FROM bedrock_subscription WHERE topic = :topic AND status = 1";
        return jdbc.query(sql, new JdbcDateTimeParameterSource("topic", topic), ROW_MAPPER);
    }

    public List<BedrockSubscription> findAll() {
        String sql = "SELECT * FROM bedrock_subscription ORDER BY id";
        return jdbc.query(sql, new JdbcDateTimeParameterSource(), ROW_MAPPER);
    }

    public int enable(Long id) {
        return jdbc.update(
                "UPDATE bedrock_subscription SET status = 1, updated_at = :now WHERE id = :id",
                new JdbcDateTimeParameterSource().addValue("id", id).addValue("now", LocalDateTime.now()));
    }

    public int disable(Long id) {
        return jdbc.update(
                "UPDATE bedrock_subscription SET status = 0, updated_at = :now WHERE id = :id",
                new JdbcDateTimeParameterSource().addValue("id", id).addValue("now", LocalDateTime.now()));
    }

    public int updateMaxRetry(Long id, int maxRetry) {
        return jdbc.update(
                "UPDATE bedrock_subscription SET max_retry = :maxRetry, updated_at = :now WHERE id = :id",
                new JdbcDateTimeParameterSource().addValue("id", id).addValue("maxRetry", maxRetry).addValue("now", LocalDateTime.now()));
    }
}
