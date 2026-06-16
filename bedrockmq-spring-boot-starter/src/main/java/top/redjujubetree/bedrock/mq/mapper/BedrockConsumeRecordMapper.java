package top.redjujubetree.bedrock.mq.mapper;

import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.BeanPropertySqlParameterSource;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSourceUtils;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import top.redjujubetree.bedrock.mq.constant.MessageStatus;
import top.redjujubetree.bedrock.mq.entity.BedrockConsumeRecord;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class BedrockConsumeRecordMapper {

    private static final RowMapper<BedrockConsumeRecord> ROW_MAPPER =
            BeanPropertyRowMapper.newInstance(BedrockConsumeRecord.class);

    private static final String INSERT_SQL =
            "INSERT INTO bedrock_consume_record " +
            "(message_id, topic, consumer, status, retry_count, max_retry, scheduled_at, deleted, created_at, updated_at) " +
            "VALUES (:messageId, :topic, :consumer, :status, :retryCount, :maxRetry, :scheduledAt, 0, :createdAt, :updatedAt)";

    private static final String JOIN_COLUMNS =
            "cr.id, cr.message_id, cr.topic, cr.consumer, cr.status, cr.node_id, " +
            "cr.retry_count, cr.max_retry, cr.error_msg, cr.scheduled_at, cr.created_at, cr.updated_at, " +
            "m.payload, m.message_source, " +
            "m.created_at AS message_created_at, m.updated_at AS message_updated_at";

    private static final String JOIN_FROM =
            " FROM bedrock_consume_record cr JOIN bedrock_message m ON m.id = cr.message_id ";

    private final NamedParameterJdbcTemplate jdbc;

    public BedrockConsumeRecordMapper(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(BedrockConsumeRecord record) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(INSERT_SQL, new BeanPropertySqlParameterSource(record), keyHolder, new String[]{"id"});
        record.setId(keyHolder.getKey().longValue());
    }

    public int insertBatch(List<BedrockConsumeRecord> records) {
        int[] results = jdbc.batchUpdate(INSERT_SQL, SqlParameterSourceUtils.createBatch(records));
        int total = 0;
        for (int r : results) total += r;
        return total;
    }

    public int tryAcquire(Long id, String nodeId) {
        String sql = "UPDATE bedrock_consume_record SET status = " + MessageStatus.PROCESSING + ", node_id = :nodeId, updated_at = :now " +
                     "WHERE id = :id AND status = " + MessageStatus.PENDING;
        return jdbc.update(sql, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("nodeId", nodeId)
                .addValue("now", LocalDateTime.now()));
    }

    public int recoverTimeoutRecords(int minutes) {
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(minutes);
        String sql = "UPDATE bedrock_consume_record " +
                     "SET status = CASE WHEN retry_count + 1 >= max_retry THEN " + MessageStatus.FAILED + " ELSE " + MessageStatus.PENDING + " END, " +
                     "    retry_count = retry_count + 1, node_id = NULL, " +
                     "    error_msg = 'Timeout: processing node may have crashed', updated_at = :now " +
                     "WHERE status = " + MessageStatus.PROCESSING + " AND updated_at < :threshold AND deleted = 0";
        return jdbc.update(sql, new MapSqlParameterSource()
                .addValue("now", LocalDateTime.now())
                .addValue("threshold", threshold));
    }

    public List<BedrockConsumeRecord> selectPending(String topic, String consumer, int limit) {
        String sql = "SELECT " + JOIN_COLUMNS + JOIN_FROM +
                     "WHERE cr.topic = :topic AND cr.consumer = :consumer " +
                     "AND cr.status = " + MessageStatus.PENDING + " AND cr.scheduled_at <= :now AND cr.deleted = 0 " +
                     "ORDER BY cr.scheduled_at ASC LIMIT :limit";
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("topic", topic)
                .addValue("consumer", consumer)
                .addValue("now", LocalDateTime.now())
                .addValue("limit", limit);
        return jdbc.query(sql, params, ROW_MAPPER);
    }

    public BedrockConsumeRecord selectByIdWithMessage(Long id) {
        String sql = "SELECT " + JOIN_COLUMNS + JOIN_FROM + "WHERE cr.id = :id AND cr.deleted = 0";
        List<BedrockConsumeRecord> results = jdbc.query(sql, new MapSqlParameterSource("id", id), ROW_MAPPER);
        return results.isEmpty() ? null : results.get(0);
    }

    public BedrockConsumeRecord selectById(Long id) {
        String sql = "SELECT * FROM bedrock_consume_record WHERE id = :id";
        List<BedrockConsumeRecord> results = jdbc.query(sql, new MapSqlParameterSource("id", id), ROW_MAPPER);
        return results.isEmpty() ? null : results.get(0);
    }

    public void markCompleted(Long id, LocalDateTime updatedAt) {
        String sql = "UPDATE bedrock_consume_record " +
                     "SET status = " + MessageStatus.COMPLETED + ", error_msg = NULL, updated_at = :updatedAt WHERE id = :id";
        jdbc.update(sql, new MapSqlParameterSource().addValue("id", id).addValue("updatedAt", updatedAt));
    }

    public void markFailed(Long id, int status, int retryCount, String errorMsg, LocalDateTime updatedAt) {
        String sql = "UPDATE bedrock_consume_record " +
                     "SET status = :status, retry_count = :retryCount, " +
                     "error_msg = :errorMsg, updated_at = :updatedAt WHERE id = :id";
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("status", status)
                .addValue("retryCount", retryCount)
                .addValue("errorMsg", errorMsg)
                .addValue("updatedAt", updatedAt);
        jdbc.update(sql, params);
    }

    /** Resets a FAILED record back to PENDING; returns 1 if updated, 0 if not found or not FAILED. */
    public int resetToPending(Long id, LocalDateTime updatedAt) {
        String sql = "UPDATE bedrock_consume_record " +
                     "SET status = " + MessageStatus.PENDING + ", retry_count = 0, node_id = NULL, error_msg = NULL, updated_at = :updatedAt " +
                     "WHERE id = :id AND status = " + MessageStatus.FAILED;
        return jdbc.update(sql, new MapSqlParameterSource().addValue("id", id).addValue("updatedAt", updatedAt));
    }

    /** Batch-resets FAILED records back to PENDING; returns number of rows updated. */
    public int batchResetToPending(List<Long> ids, LocalDateTime updatedAt) {
        String sql = "UPDATE bedrock_consume_record " +
                     "SET status = " + MessageStatus.PENDING + ", retry_count = 0, node_id = NULL, error_msg = NULL, updated_at = :updatedAt " +
                     "WHERE id IN (:ids) AND status = " + MessageStatus.FAILED;
        return jdbc.update(sql, new MapSqlParameterSource().addValue("ids", ids).addValue("updatedAt", updatedAt));
    }

    /** Cancels a PENDING record by moving it to FAILED; returns 1 if updated, 0 if not found or not PENDING. */
    public int cancelPending(Long id, LocalDateTime updatedAt) {
        String sql = "UPDATE bedrock_consume_record " +
                     "SET status = " + MessageStatus.FAILED + ", error_msg = 'Cancelled via admin API', updated_at = :updatedAt " +
                     "WHERE id = :id AND status = " + MessageStatus.PENDING;
        return jdbc.update(sql, new MapSqlParameterSource().addValue("id", id).addValue("updatedAt", updatedAt));
    }

    public int updateMaxRetry(Long id, int maxRetry) {
        String sql = "UPDATE bedrock_consume_record SET max_retry = :maxRetry, updated_at = :now WHERE id = :id";
        return jdbc.update(sql, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("maxRetry", maxRetry)
                .addValue("now", LocalDateTime.now()));
    }

    public int batchUpdateMaxRetry(List<Long> ids, int maxRetry) {
        String sql = "UPDATE bedrock_consume_record SET max_retry = :maxRetry, updated_at = :now WHERE id IN (:ids)";
        return jdbc.update(sql, new MapSqlParameterSource()
                .addValue("ids", ids)
                .addValue("maxRetry", maxRetry)
                .addValue("now", LocalDateTime.now()));
    }

    public void deleteById(Long id) {
        String sql = "UPDATE bedrock_consume_record SET deleted = 1, updated_at = :now WHERE id = :id";
        jdbc.update(sql, new MapSqlParameterSource().addValue("id", id).addValue("now", LocalDateTime.now()));
    }

    public void deleteAll() {
        jdbc.getJdbcTemplate().update("DELETE FROM bedrock_consume_record");
    }

    public List<BedrockConsumeRecord> listMessages(String topic, String consumer, Integer status,
                                                    long offset, long limit) {
        StringBuilder sql = new StringBuilder(
                "SELECT cr.id, cr.message_id, cr.topic, cr.consumer, cr.status, cr.node_id, " +
                "cr.retry_count, cr.max_retry, cr.error_msg, cr.scheduled_at, cr.created_at, cr.updated_at, " +
                "m.message_source " +
                "FROM bedrock_consume_record cr JOIN bedrock_message m ON m.id = cr.message_id");
        MapSqlParameterSource params = new MapSqlParameterSource();
        List<String> conditions = new ArrayList<>();
        conditions.add("cr.deleted = 0");
        if (topic != null && !topic.isEmpty()) {
            conditions.add("cr.topic = :topic");
            params.addValue("topic", topic);
        }
        if (consumer != null && !consumer.isEmpty()) {
            conditions.add("cr.consumer = :consumer");
            params.addValue("consumer", consumer);
        }
        if (status != null) {
            conditions.add("cr.status = :status");
            params.addValue("status", status);
        }
        sql.append(" WHERE ").append(String.join(" AND ", conditions));
        sql.append(" ORDER BY cr.id DESC LIMIT :limit OFFSET :offset");
        params.addValue("limit", limit).addValue("offset", offset);
        return jdbc.query(sql.toString(), params, ROW_MAPPER);
    }

    public long countMessages(String topic, String consumer, Integer status) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM bedrock_consume_record");
        MapSqlParameterSource params = new MapSqlParameterSource();
        List<String> conditions = new ArrayList<>();
        conditions.add("deleted = 0");
        if (topic != null && !topic.isEmpty()) {
            conditions.add("topic = :topic");
            params.addValue("topic", topic);
        }
        if (consumer != null && !consumer.isEmpty()) {
            conditions.add("consumer = :consumer");
            params.addValue("consumer", consumer);
        }
        if (status != null) {
            conditions.add("status = :status");
            params.addValue("status", status);
        }
        sql.append(" WHERE ").append(String.join(" AND ", conditions));
        Long count = jdbc.queryForObject(sql.toString(), params, Long.class);
        return count != null ? count : 0L;
    }

    public List<Map<String, Object>> selectStatusCountByTopicAndConsumer() {
        String sql = "SELECT topic, consumer, status, COUNT(*) AS count " +
                     "FROM bedrock_consume_record WHERE deleted = 0 GROUP BY topic, consumer, status";
        return jdbc.getJdbcTemplate().queryForList(sql);
    }
}
