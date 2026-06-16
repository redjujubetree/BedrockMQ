package top.redjujubetree.bedrock.mq.dialect;

public class SqliteDialect implements SqlDialect {

    @Override
    public String upsertSubscriptionSql() {
        return "INSERT OR IGNORE INTO bedrock_subscription (topic, consumer, max_retry, status, created_at, updated_at) " +
               "VALUES (:topic, :consumer, :maxRetry, 1, :now, :now)";
    }
}
