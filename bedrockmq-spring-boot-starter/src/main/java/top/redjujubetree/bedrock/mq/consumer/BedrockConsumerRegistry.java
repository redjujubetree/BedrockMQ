package top.redjujubetree.bedrock.mq.consumer;

import top.redjujubetree.bedrock.mq.annotation.BedrockConsumer;
import top.redjujubetree.bedrock.mq.mapper.BedrockSubscriptionMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.AnnotationUtils;

import javax.annotation.PostConstruct;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public class BedrockConsumerRegistry {

    private static final Logger log = LoggerFactory.getLogger(BedrockConsumerRegistry.class);
    private static final char KEY_SEPARATOR = ':';

    private final ApplicationContext applicationContext;
    private final BedrockSubscriptionMapper subscriptionMapper;

    /** Key: "topic:consumer" */
    private final Map<String, BedrockMessageConsumer> registry = new HashMap<>();

    public BedrockConsumerRegistry(ApplicationContext applicationContext, BedrockSubscriptionMapper subscriptionMapper) {
        this.applicationContext = applicationContext;
        this.subscriptionMapper = subscriptionMapper;
    }

    @PostConstruct
    public void init() {
        Map<String, Object> beans = applicationContext.getBeansWithAnnotation(BedrockConsumer.class);
        for (Map.Entry<String, Object> entry : beans.entrySet()) {
            Object bean = entry.getValue();
            if (!(bean instanceof BedrockMessageConsumer)) {
                continue;
            }
            BedrockConsumer annotation = AnnotationUtils.findAnnotation(
                    AopUtils.getTargetClass(bean), BedrockConsumer.class);
            if (annotation == null) {
                continue;
            }
            String consumerName = annotation.value();
            String topic = annotation.topic();
            if (topic.isEmpty() || consumerName.isEmpty()) {
                throw new IllegalStateException(
                        "@BedrockConsumer on " + AopUtils.getTargetClass(bean).getName()
                                + " has an empty topic or consumer name");
            }
            int maxRetry = annotation.maxRetry();

            String registryKey = key(topic, consumerName);
            if (registry.containsKey(registryKey)) {
                throw new IllegalStateException(
                        "Duplicate @BedrockConsumer registration for topic=" + topic
                                + " consumer=" + consumerName);
            }
            registry.put(registryKey, (BedrockMessageConsumer) bean);
            subscriptionMapper.upsert(topic, consumerName, maxRetry);
            log.info("Registered consumer: topic={} consumer={} class={}", topic, consumerName,
                    AopUtils.getTargetClass(bean).getSimpleName());
        }
    }

    public BedrockMessageConsumer getConsumer(String topic, String consumerName) {
        return registry.get(key(topic, consumerName));
    }

    /** Returns all registered (topic, consumer) keys as "topic:consumer" strings. */
    public Set<String> getRegisteredKeys() {
        return Collections.unmodifiableSet(registry.keySet());
    }

    public static String key(String topic, String consumerName) {
        return topic + KEY_SEPARATOR + consumerName;
    }

    public static String[] splitKey(String key) {
        int idx = key.indexOf(KEY_SEPARATOR);
        return new String[]{key.substring(0, idx), key.substring(idx + 1)};
    }
}
