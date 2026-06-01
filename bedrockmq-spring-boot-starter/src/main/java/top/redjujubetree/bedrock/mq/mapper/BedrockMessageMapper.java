package top.redjujubetree.bedrock.mq.mapper;

import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.BeanPropertySqlParameterSource;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSourceUtils;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import top.redjujubetree.bedrock.mq.entity.BedrockMessage;

import java.util.List;
import java.util.Map;

public class BedrockMessageMapper {

    private static final RowMapper<BedrockMessage> ROW_MAPPER =
            BeanPropertyRowMapper.newInstance(BedrockMessage.class);

    private static final String INSERT_SQL =
            "INSERT INTO bedrock_message (topic, message_source, payload, created_at, updated_at) " +
            "VALUES (:topic, :messageSource, :payload, :createdAt, :updatedAt)";

    private final NamedParameterJdbcTemplate jdbc;

    public BedrockMessageMapper(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(BedrockMessage msg) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(INSERT_SQL, new BeanPropertySqlParameterSource(msg), keyHolder, new String[]{"id"});
        msg.setId(keyHolder.getKey().longValue());
    }

    public BedrockMessage selectById(Long id) {
        String sql = "SELECT * FROM bedrock_message WHERE id = :id";
        List<BedrockMessage> results = jdbc.query(sql, new MapSqlParameterSource("id", id), ROW_MAPPER);
        return results.isEmpty() ? null : results.get(0);
    }

    /** Inserts each message individually to ensure generated IDs are populated on the objects. */
    public int insertBatch(List<BedrockMessage> messages) {
        for (BedrockMessage msg : messages) {
            insert(msg);
        }
        return messages.size();
    }

    public void deleteAll() {
        jdbc.getJdbcTemplate().update("DELETE FROM bedrock_message");
    }
}
