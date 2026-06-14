package top.redjujubetree.bedrock.mq.config;

import top.redjujubetree.bedrock.mq.consumer.MessageConsumer;
import top.redjujubetree.bedrock.mq.consumer.PerTypePollingManager;
import top.redjujubetree.bedrock.mq.dialect.MySqlDialect;
import top.redjujubetree.bedrock.mq.dialect.SqlDialect;
import top.redjujubetree.bedrock.mq.dialect.SqliteDialect;
import top.redjujubetree.bedrock.mq.mapper.BedrockConsumeRecordMapper;
import top.redjujubetree.bedrock.mq.mapper.BedrockMessageMapper;
import top.redjujubetree.bedrock.mq.mapper.BedrockSubscriptionMapper;
import top.redjujubetree.bedrock.mq.processor.ProcessorRegistry;
import top.redjujubetree.bedrock.mq.producer.MessageProducer;
import top.redjujubetree.bedrock.mq.recovery.TimeoutRecoveryTask;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.JdbcUtils;
import org.springframework.jdbc.support.MetaDataAccessException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.DatabaseMetaData;

@Configuration
@ConditionalOnProperty(prefix = "bedrock.mq", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(BedrockMqProperties.class)
public class BedrockAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(BedrockAutoConfiguration.class);

    @Bean
    public BedrockMessageMapper bedrockMessageMapper(NamedParameterJdbcTemplate jdbc) {
        return new BedrockMessageMapper(jdbc);
    }

    @Bean
    public BedrockConsumeRecordMapper bedrockConsumeRecordMapper(NamedParameterJdbcTemplate jdbc) {
        return new BedrockConsumeRecordMapper(jdbc);
    }

    @Bean
    public SqlDialect sqlDialect(DataSource dataSource, BedrockMqProperties properties) {
        String setting = properties.getDbDialect();
        if ("mysql".equalsIgnoreCase(setting))  return new MySqlDialect();
        if ("sqlite".equalsIgnoreCase(setting)) return new SqliteDialect();
        if (!"auto".equalsIgnoreCase(setting)) {
            log.warn("Unrecognized bedrock.mq.db-dialect '{}', falling back to auto-detection", setting);
        }
        String productName = null;
        try {
            productName = JdbcUtils.extractDatabaseMetaData(dataSource, DatabaseMetaData::getDatabaseProductName);
        } catch (MetaDataAccessException e) {
            log.warn("Failed to detect database dialect from JDBC metadata, falling back to MySQL: {}", e.getMessage());
        }
        if (productName == null) {
            log.warn("Database product name is null, falling back to MySQL dialect");
            return new MySqlDialect();
        }
        return productName.toLowerCase().contains("sqlite") ? new SqliteDialect() : new MySqlDialect();
    }

    @Bean
    public BedrockSubscriptionMapper bedrockSubscriptionMapper(
            NamedParameterJdbcTemplate jdbc, SqlDialect sqlDialect) {
        return new BedrockSubscriptionMapper(jdbc, sqlDialect);
    }

    @Bean
    public ProcessorRegistry processorRegistry(ApplicationContext applicationContext,
                                               BedrockSubscriptionMapper subscriptionMapper) {
        return new ProcessorRegistry(applicationContext, subscriptionMapper);
    }

    @Bean
    public MessageConsumer messageConsumer(BedrockConsumeRecordMapper consumeRecordMapper,
                                          ProcessorRegistry registry,
                                          BedrockMqProperties properties) {
        return new MessageConsumer(consumeRecordMapper, registry, properties);
    }

    @Bean
    public MessageProducer messageProducer(BedrockMessageMapper messageMapper,
                                           BedrockConsumeRecordMapper consumeRecordMapper,
                                           BedrockSubscriptionMapper subscriptionMapper,
                                           ObjectMapper objectMapper) {
        return new MessageProducer(messageMapper, consumeRecordMapper, subscriptionMapper, objectMapper);
    }

    @Bean
    public PerTypePollingManager perTypePollingManager(BedrockConsumeRecordMapper consumeRecordMapper,
                                                       MessageConsumer consumer,
                                                       ProcessorRegistry registry,
                                                       BedrockMqProperties properties) {
        return new PerTypePollingManager(consumeRecordMapper, consumer, registry, properties);
    }

    @Bean
    public TimeoutRecoveryTask timeoutRecoveryTask(BedrockConsumeRecordMapper consumeRecordMapper,
                                                   BedrockMqProperties properties) {
        return new TimeoutRecoveryTask(consumeRecordMapper, properties);
    }
}
