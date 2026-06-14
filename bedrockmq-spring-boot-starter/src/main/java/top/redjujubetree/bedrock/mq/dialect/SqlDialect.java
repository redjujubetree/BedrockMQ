package top.redjujubetree.bedrock.mq.dialect;

public interface SqlDialect {
    /**
     * Returns the upsert SQL for bedrock_subscription.
     * Named parameters available: :topic, :consumer, :maxRetry, :now
     */
    String upsertSubscriptionSql();
}
