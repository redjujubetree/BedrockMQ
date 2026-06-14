package top.redjujubetree.bedrock.mq.dialect;

public class SqliteDialect implements SqlDialect {

    @Override
    public String upsertSubscriptionSql() {
        return "INSERT INTO bedrock_subscription (topic, consumer, max_retry, status, created_at, updated_at) " +
               "VALUES (:topic, :consumer, :maxRetry, 1, :now, :now) " +
               "ON CONFLICT(topic, consumer) DO UPDATE SET " +
               "max_retry = excluded.max_retry, updated_at = :now";
    }
}
