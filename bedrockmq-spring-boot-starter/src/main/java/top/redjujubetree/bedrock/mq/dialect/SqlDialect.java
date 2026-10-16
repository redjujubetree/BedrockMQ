package top.redjujubetree.bedrock.mq.dialect;

public interface SqlDialect {
    /**
     * Returns the upsert SQL for bedrock_subscription.
     * Named parameters available: :topic, :consumer, :maxRetry, :now
     */
    String upsertSubscriptionSql();

    /** Insert with an explicit ID while preserving an existing (topic, consumer) row. */
    default String insertSubscriptionWithIdSql() {
        return "INSERT INTO bedrock_subscription " +
               "(id, topic, consumer, max_retry, status, created_at, updated_at) " +
               "VALUES (:id, :topic, :consumer, :maxRetry, 1, :now, :now)";
    }
}
