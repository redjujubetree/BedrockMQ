package top.redjujubetree.bedrock.mq.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.InetAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@ConfigurationProperties(prefix = "bedrock.mq")
public class BedrockMqProperties {

    private String nodeId = generateDefaultNodeId();
    private int batchSize = 10;
    private long pollIntervalMs = 1000;
    private int processingTimeoutMinutes = 15;
    private int defaultConcurrency = 1;
    private Map<String, Integer> typeConcurrency = new HashMap<>();
    private String dbDialect = "auto";

    private static String generateDefaultNodeId() {
        try {
            String host = InetAddress.getLocalHost().getHostName();
            return host + "-" + UUID.randomUUID().toString().substring(0, 8);
        } catch (Exception e) {
            return "node-" + UUID.randomUUID().toString().substring(0, 8);
        }
    }

    public int getConcurrencyFor(String key) {
        return typeConcurrency.getOrDefault(key, defaultConcurrency);
    }

    public String getNodeId() { return nodeId; }
    public void setNodeId(String nodeId) { this.nodeId = nodeId; }

    public int getBatchSize() { return batchSize; }
    public void setBatchSize(int batchSize) { this.batchSize = batchSize; }

    public long getPollIntervalMs() { return pollIntervalMs; }
    public void setPollIntervalMs(long pollIntervalMs) { this.pollIntervalMs = pollIntervalMs; }

    public int getProcessingTimeoutMinutes() { return processingTimeoutMinutes; }
    public void setProcessingTimeoutMinutes(int processingTimeoutMinutes) { this.processingTimeoutMinutes = processingTimeoutMinutes; }

    public int getDefaultConcurrency() { return defaultConcurrency; }
    public void setDefaultConcurrency(int defaultConcurrency) { this.defaultConcurrency = defaultConcurrency; }

    public Map<String, Integer> getTypeConcurrency() { return typeConcurrency; }
    public void setTypeConcurrency(Map<String, Integer> typeConcurrency) { this.typeConcurrency = typeConcurrency; }

    public String getDbDialect() { return dbDialect; }
    public void setDbDialect(String dbDialect) { this.dbDialect = dbDialect; }
}
