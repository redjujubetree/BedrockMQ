package top.redjujubetree.bedrock.mq.mapper;

import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import top.redjujubetree.bedrock.mq.entity.BedrockSubscription;

import java.util.List;

public class BedrockSubscriptionMapper {

    private static final RowMapper<BedrockSubscription> ROW_MAPPER =
            BeanPropertyRowMapper.newInstance(BedrockSubscription.class);

    private final NamedParameterJdbcTemplate jdbc;

    public BedrockSubscriptionMapper(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Upsert: insert on new, update max_retry on conflict. Status is NOT overwritten (preserves admin disable). */
    public void upsert(String topic, String consumer, int maxRetry) {
        String sql = "INSERT INTO bedrock_subscription (topic, consumer, max_retry, status) " +
                     "VALUES (:topic, :consumer, :maxRetry, 1) " +
                     "ON DUPLICATE KEY UPDATE max_retry = VALUES(max_retry), updated_at = NOW()";
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("topic", topic)
                .addValue("consumer", consumer)
                .addValue("maxRetry", maxRetry);
        jdbc.update(sql, params);
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
        String sql = "UPDATE bedrock_subscription SET status = 1, updated_at = NOW() WHERE id = :id";
        return jdbc.update(sql, new MapSqlParameterSource("id", id));
    }

    public int disable(Long id) {
        String sql = "UPDATE bedrock_subscription SET status = 0, updated_at = NOW() WHERE id = :id";
        return jdbc.update(sql, new MapSqlParameterSource("id", id));
    }
}
