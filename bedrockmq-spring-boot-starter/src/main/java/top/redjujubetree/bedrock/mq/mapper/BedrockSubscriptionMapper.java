package top.redjujubetree.bedrock.mq.mapper;

import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import top.redjujubetree.bedrock.mq.dialect.SqlDialect;
import top.redjujubetree.bedrock.mq.entity.BedrockSubscription;

import java.time.LocalDateTime;
import java.util.List;

public class BedrockSubscriptionMapper {

    private static final RowMapper<BedrockSubscription> ROW_MAPPER =
            BeanPropertyRowMapper.newInstance(BedrockSubscription.class);

    private final NamedParameterJdbcTemplate jdbc;
    private final SqlDialect dialect;

    public BedrockSubscriptionMapper(NamedParameterJdbcTemplate jdbc, SqlDialect dialect) {
        this.jdbc = jdbc;
        this.dialect = dialect;
    }

    /** Upsert: insert on new, update max_retry on conflict. Status is NOT overwritten (preserves admin disable). */
    public void upsert(String topic, String consumer, int maxRetry) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("topic", topic)
                .addValue("consumer", consumer)
                .addValue("maxRetry", maxRetry)
                .addValue("now", LocalDateTime.now());
        jdbc.update(dialect.upsertSubscriptionSql(), params);
    }

    /** Returns only enabled (status=1) subscriptions for the given topic. */
    public List<BedrockSubscription> findEnabledByTopic(String topic) {
        String sql = "SELECT * FROM bedrock_subscription WHERE topic = :topic AND status = 1";
        return jdbc.query(sql, new MapSqlParameterSource("topic", topic), ROW_MAPPER);
    }

    public List<BedrockSubscription> findAll() {
        String sql = "SELECT * FROM bedrock_subscription ORDER BY id";
        return jdbc.query(sql, new MapSqlParameterSource(), ROW_MAPPER);
    }

    public int enable(Long id) {
        return jdbc.update(
                "UPDATE bedrock_subscription SET status = 1, updated_at = :now WHERE id = :id",
                new MapSqlParameterSource().addValue("id", id).addValue("now", LocalDateTime.now()));
    }

    public int disable(Long id) {
        return jdbc.update(
                "UPDATE bedrock_subscription SET status = 0, updated_at = :now WHERE id = :id",
                new MapSqlParameterSource().addValue("id", id).addValue("now", LocalDateTime.now()));
    }

    public int updateMaxRetry(Long id, int maxRetry) {
        return jdbc.update(
                "UPDATE bedrock_subscription SET max_retry = :maxRetry, updated_at = :now WHERE id = :id",
                new MapSqlParameterSource().addValue("id", id).addValue("maxRetry", maxRetry).addValue("now", LocalDateTime.now()));
    }
}
