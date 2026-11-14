package top.redjujubetree.bedrock.mq.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;
import top.redjujubetree.bedrock.mq.consumer.BedrockMessageProcessor;
import top.redjujubetree.bedrock.mq.consumer.PerTypePollingManager;
import top.redjujubetree.bedrock.mq.mapper.BedrockConsumeRecordMapper;
import top.redjujubetree.bedrock.mq.producer.MessageProducer;
import top.redjujubetree.bedrock.mq.client.BedrockHttpClient;
import top.redjujubetree.bedrock.mq.controller.BedrockMessageController;
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
            assertThat(context).doesNotHaveBean(BedrockMessageProcessor.class);
            assertThat(context).doesNotHaveBean(MessageProducer.class);
            assertThat(context).doesNotHaveBean(BedrockHttpClient.class);
            assertThat(context).doesNotHaveBean(BedrockMessageController.class);
            assertThat(context).doesNotHaveBean(PerTypePollingManager.class);
            assertThat(context).doesNotHaveBean(TimeoutRecoveryTask.class);
        });
    }

    @Test
    void bindsInboundClientKeySeparatelyFromOutboundClient() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("bedrock.mq.http.server.enabled", "true")
                .withProperty("bedrock.mq.http.server.encryption-key", "shared-key")
                .withProperty("bedrock.mq.http.server.algorithm", "AES-256-GCM")
                .withProperty("bedrock.mq.http.server.clients.orders.encryption-key", "inbound-key")
                .withProperty("bedrock.mq.http.server.clients.orders.algorithm", "AES-128-GCM");
        BedrockMqProperties properties = Binder.get(environment)
                .bind("bedrock.mq", BedrockMqProperties.class).get();
        assertThat(properties.getHttp().getServer().isEnabled()).isTrue();
        assertThat(properties.getHttp().getServer().getEncryptionKey()).isEqualTo("shared-key");
        assertThat(properties.getHttp().getServer().getAlgorithm()).isEqualTo("AES-256-GCM");
        assertThat(properties.getHttp().getServer().getClients().get("orders").getEncryptionKey())
                .isEqualTo("inbound-key");
        assertThat(properties.getHttp().getServer().getClients().get("orders").getAlgorithm())
                .isEqualTo("AES-128-GCM");
    }
}
