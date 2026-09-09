package top.redjujubetree.bedrock.mq.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import top.redjujubetree.bedrock.mq.consumer.MessageProcessor;
import top.redjujubetree.bedrock.mq.consumer.PerTypePollingManager;
import top.redjujubetree.bedrock.mq.mapper.BedrockConsumeRecordMapper;
import top.redjujubetree.bedrock.mq.producer.MessageProducer;
import top.redjujubetree.bedrock.mq.recovery.TimeoutRecoveryTask;

import static org.assertj.core.api.Assertions.assertThat;

class BedrockAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(BedrockAutoConfiguration.class));

    @Test
    void disabledPropertyPreventsAllInfrastructureBeansFromLoading() {
        contextRunner.withPropertyValues("bedrock.mq.enabled=false").run(context -> {
            assertThat(context).doesNotHaveBean(BedrockMqProperties.class);
            assertThat(context).doesNotHaveBean(BedrockConsumeRecordMapper.class);
            assertThat(context).doesNotHaveBean(MessageProcessor.class);
            assertThat(context).doesNotHaveBean(MessageProducer.class);
            assertThat(context).doesNotHaveBean(PerTypePollingManager.class);
            assertThat(context).doesNotHaveBean(TimeoutRecoveryTask.class);
        });
    }
}
