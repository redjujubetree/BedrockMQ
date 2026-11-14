package top.redjujubetree.bedrock.mq.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import top.redjujubetree.bedrock.mq.client.BedrockHttpClient;

@Configuration
@ConditionalOnProperty(prefix = "bedrock.mq.http.client", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(BedrockHttpClientProperties.class)
public class BedrockClientAutoConfiguration {

    @Bean
    public BedrockHttpClient bedrockHttpClient(BedrockHttpClientProperties properties, ObjectMapper objectMapper) {
        return new BedrockHttpClient(properties, objectMapper);
    }
}
