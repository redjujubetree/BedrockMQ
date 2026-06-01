package top.redjujubetree.bedrock.mq.config;

import top.redjujubetree.bedrock.mq.consumer.MessageConsumer;
import top.redjujubetree.bedrock.mq.consumer.PerTypePollingManager;
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

@Configuration
@ConditionalOnProperty(prefix = "bedrock.mq", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(BedrockMqProperties.class)
public class BedrockAutoConfiguration {

    @Bean
    public BedrockMessageMapper bedrockMessageMapper(NamedParameterJdbcTemplate jdbc) {
        return new BedrockMessageMapper(jdbc);
    }

    @Bean
    public BedrockConsumeRecordMapper bedrockConsumeRecordMapper(NamedParameterJdbcTemplate jdbc) {
        return new BedrockConsumeRecordMapper(jdbc);
    }

    @Bean
    public BedrockSubscriptionMapper bedrockSubscriptionMapper(NamedParameterJdbcTemplate jdbc) {
        return new BedrockSubscriptionMapper(jdbc);
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
