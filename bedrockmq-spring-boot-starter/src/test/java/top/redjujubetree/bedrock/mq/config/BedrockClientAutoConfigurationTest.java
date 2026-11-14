package top.redjujubetree.bedrock.mq.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import top.redjujubetree.bedrock.mq.client.BedrockHttpClient;
import top.redjujubetree.bedrock.mq.controller.BedrockMessageController;
import top.redjujubetree.bedrock.mq.producer.MessageProducer;

import static org.assertj.core.api.Assertions.assertThat;

class BedrockClientAutoConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(BedrockAutoConfiguration.class, BedrockClientAutoConfiguration.class))
            .withBean(ObjectMapper.class, ObjectMapper::new);

    @Test
    void clientWorksWithServerAndCoreDisabled() {
        runner.withPropertyValues(
                "bedrock.mq.enabled=false",
                "bedrock.mq.http.server.enabled=false",
                "bedrock.mq.http.client.enabled=true",
                "bedrock.mq.http.clients.orders.address=https://mq.example.com",
                "bedrock.mq.http.clients.orders.port=443",
                "bedrock.mq.http.clients.orders.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
                .run(context -> {
                    assertThat(context).hasSingleBean(BedrockHttpClient.class);
                    assertThat(context).hasSingleBean(BedrockHttpClientProperties.class);
                    assertThat(context).doesNotHaveBean(BedrockMqProperties.class);
                    assertThat(context).doesNotHaveBean(MessageProducer.class);
                    assertThat(context).doesNotHaveBean(BedrockMessageController.class);
                });
    }

    @Test
    void clientIsAbsentUnlessEnabled() {
        runner.withPropertyValues("bedrock.mq.enabled=false").run(context -> {
            assertThat(context).doesNotHaveBean(BedrockHttpClient.class);
            assertThat(context).doesNotHaveBean(BedrockHttpClientProperties.class);
        });
    }
}
