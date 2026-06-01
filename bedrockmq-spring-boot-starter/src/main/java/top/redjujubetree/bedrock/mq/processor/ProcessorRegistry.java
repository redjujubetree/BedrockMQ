package top.redjujubetree.bedrock.mq.processor;

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

public class ProcessorRegistry {

    private static final Logger log = LoggerFactory.getLogger(ProcessorRegistry.class);

    private final ApplicationContext applicationContext;
    private final BedrockSubscriptionMapper subscriptionMapper;

    /** Key: "topic:consumer" */
    private final Map<String, MessageProcessor> registry = new HashMap<>();

    public ProcessorRegistry(ApplicationContext applicationContext, BedrockSubscriptionMapper subscriptionMapper) {
        this.applicationContext = applicationContext;
        this.subscriptionMapper = subscriptionMapper;
    }

    @PostConstruct
    public void init() {
        Map<String, Object> beans = applicationContext.getBeansWithAnnotation(BedrockConsumer.class);
        for (Map.Entry<String, Object> entry : beans.entrySet()) {
            Object bean = entry.getValue();
            if (!(bean instanceof MessageProcessor)) {
                continue;
            }
            BedrockConsumer annotation = AnnotationUtils.findAnnotation(
                    AopUtils.getTargetClass(bean), BedrockConsumer.class);
            if (annotation == null) {
                continue;
            }
            String consumer = annotation.value();
            String topic = annotation.topic();
            if (topic.isEmpty() || consumer.isEmpty()) {
                throw new IllegalStateException(
                        "@BedrockConsumer on " + AopUtils.getTargetClass(bean).getName()
                                + " has an empty topic or consumer name");
            }
            int maxRetry = annotation.maxRetry();

            registry.put(key(topic, consumer), (MessageProcessor) bean);
            subscriptionMapper.upsert(topic, consumer, maxRetry);
            log.info("Registered processor: topic={} consumer={} class={}", topic, consumer,
                    AopUtils.getTargetClass(bean).getSimpleName());
        }
    }

    public MessageProcessor getProcessor(String topic, String consumer) {
        return registry.get(key(topic, consumer));
    }

    /** Returns all registered (topic, consumer) keys as "topic:consumer" strings. */
    public Set<String> getRegisteredKeys() {
        return Collections.unmodifiableSet(registry.keySet());
    }

    public static String key(String topic, String consumer) {
        return topic + ":" + consumer;
    }

    public static String[] splitKey(String key) {
        int idx = key.indexOf(':');
        return new String[]{key.substring(0, idx), key.substring(idx + 1)};
    }
}
