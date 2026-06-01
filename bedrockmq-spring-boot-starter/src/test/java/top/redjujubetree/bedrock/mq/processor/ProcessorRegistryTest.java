package top.redjujubetree.bedrock.mq.processor;

import top.redjujubetree.bedrock.mq.annotation.BedrockConsumer;
import top.redjujubetree.bedrock.mq.entity.BedrockMessage;
import top.redjujubetree.bedrock.mq.mapper.BedrockSubscriptionMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationContext;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProcessorRegistryTest {

    @Mock ApplicationContext ctx;
    @Mock BedrockSubscriptionMapper subscriptionMapper;

    @BedrockConsumer(value = "order", topic = "order")
    static class OrderProcessor implements MessageProcessor {
        @Override public void process(BedrockMessage message) {}
    }

    @BedrockConsumer(value = "notify", topic = "notify")
    static class NotifyProcessor implements MessageProcessor {
        @Override public void process(BedrockMessage message) {}
    }

    /** fan-out: separate consumer on same topic */
    @BedrockConsumer(value = "inventory", topic = "order-created")
    static class InventoryProcessor implements MessageProcessor {
        @Override public void process(BedrockMessage message) {}
    }

    @BedrockConsumer(value = "ignored", topic = "ignored")
    static class NotAProcessor {}

    /** blank consumer name must throw */
    @BedrockConsumer(value = "", topic = "some-topic")
    static class BlankConsumerProcessor implements MessageProcessor {
        @Override public void process(BedrockMessage message) {}
    }

    /** blank topic must throw */
    @BedrockConsumer(value = "some-consumer", topic = "")
    static class BlankTopicProcessor implements MessageProcessor {
        @Override public void process(BedrockMessage message) {}
    }

    @Test
    void init_registersAllMessageProcessorBeans() {
        Map<String, Object> beans = new HashMap<>();
        beans.put("orderProcessor", new OrderProcessor());
        beans.put("notifyProcessor", new NotifyProcessor());
        when(ctx.getBeansWithAnnotation(BedrockConsumer.class)).thenReturn(beans);

        ProcessorRegistry registry = new ProcessorRegistry(ctx, subscriptionMapper);
        registry.init();

        assertThat(registry.getProcessor("order", "order")).isInstanceOf(OrderProcessor.class);
        assertThat(registry.getProcessor("notify", "notify")).isInstanceOf(NotifyProcessor.class);
    }

    @Test
    void init_upsertsSingleSubscriptionPerProcessor() {
        Map<String, Object> beans = new HashMap<>();
        beans.put("orderProcessor", new OrderProcessor());
        when(ctx.getBeansWithAnnotation(BedrockConsumer.class)).thenReturn(beans);

        ProcessorRegistry registry = new ProcessorRegistry(ctx, subscriptionMapper);
        registry.init();

        verify(subscriptionMapper).upsert("order", "order", 3);
    }

    @Test
    void init_resolvesTopicFromAnnotationWhenExplicitlySet() {
        Map<String, Object> beans = new HashMap<>();
        beans.put("inventoryProcessor", new InventoryProcessor());
        when(ctx.getBeansWithAnnotation(BedrockConsumer.class)).thenReturn(beans);

        ProcessorRegistry registry = new ProcessorRegistry(ctx, subscriptionMapper);
        registry.init();

        assertThat(registry.getProcessor("order-created", "inventory")).isInstanceOf(InventoryProcessor.class);
        verify(subscriptionMapper).upsert("order-created", "inventory", 3);
    }

    @Test
    void getProcessor_returnsNullForUnregisteredTopicConsumerPair() {
        when(ctx.getBeansWithAnnotation(BedrockConsumer.class)).thenReturn(new HashMap<>());

        ProcessorRegistry registry = new ProcessorRegistry(ctx, subscriptionMapper);
        registry.init();

        assertThat(registry.getProcessor("unknown", "consumer")).isNull();
    }

    @Test
    void init_ignoresBeansThatDoNotImplementMessageProcessor() {
        Map<String, Object> beans = new HashMap<>();
        beans.put("notAProcessor", new NotAProcessor());
        when(ctx.getBeansWithAnnotation(BedrockConsumer.class)).thenReturn(beans);

        ProcessorRegistry registry = new ProcessorRegistry(ctx, subscriptionMapper);
        registry.init();

        assertThat(registry.getProcessor("ignored", "ignored")).isNull();
        verify(subscriptionMapper, never()).upsert(any(), any(), anyInt());
    }

    @Test
    void init_registersNothingWhenContextHasNoAnnotatedBeans() {
        when(ctx.getBeansWithAnnotation(BedrockConsumer.class)).thenReturn(new HashMap<>());

        ProcessorRegistry registry = new ProcessorRegistry(ctx, subscriptionMapper);
        registry.init();

        assertThat(registry.getRegisteredKeys()).isEmpty();
    }

    @Test
    void init_throwsWhenConsumerNameIsBlank() {
        Map<String, Object> beans = new HashMap<>();
        beans.put("blankConsumerProcessor", new BlankConsumerProcessor());
        when(ctx.getBeansWithAnnotation(BedrockConsumer.class)).thenReturn(beans);

        ProcessorRegistry registry = new ProcessorRegistry(ctx, subscriptionMapper);

        assertThatThrownBy(registry::init)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("empty topic or consumer");
    }

    @Test
    void init_throwsWhenTopicIsBlank() {
        Map<String, Object> beans = new HashMap<>();
        beans.put("blankTopicProcessor", new BlankTopicProcessor());
        when(ctx.getBeansWithAnnotation(BedrockConsumer.class)).thenReturn(beans);

        ProcessorRegistry registry = new ProcessorRegistry(ctx, subscriptionMapper);

        assertThatThrownBy(registry::init)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("empty topic or consumer");
    }
}
