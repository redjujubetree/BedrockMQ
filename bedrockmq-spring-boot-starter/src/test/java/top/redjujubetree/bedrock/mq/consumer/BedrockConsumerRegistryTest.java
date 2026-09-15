package top.redjujubetree.bedrock.mq.consumer;

import top.redjujubetree.bedrock.mq.annotation.BedrockConsumer;
import top.redjujubetree.bedrock.mq.entity.BedrockMessage;
import top.redjujubetree.bedrock.mq.mapper.BedrockSubscriptionMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.context.ApplicationContext;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BedrockConsumerRegistryTest {

    @Mock ApplicationContext ctx;
    @Mock BedrockSubscriptionMapper subscriptionMapper;

    @BedrockConsumer(value = "order", topic = "order")
    static class OrderConsumer implements BedrockMessageConsumer {
        @Override public void consume(BedrockMessage message) {}
    }

    @BedrockConsumer(value = "order", topic = "order")
    static class DuplicateOrderConsumer implements BedrockMessageConsumer {
        @Override public void consume(BedrockMessage message) {}
    }

    @BedrockConsumer(value = "notify", topic = "notify")
    static class NotifyConsumer implements BedrockMessageConsumer {
        @Override public void consume(BedrockMessage message) {}
    }

    /** fan-out: separate consumer on same topic */
    @BedrockConsumer(value = "inventory", topic = "order-created")
    static class InventoryConsumer implements BedrockMessageConsumer {
        @Override public void consume(BedrockMessage message) {}
    }

    @BedrockConsumer(value = "ignored", topic = "ignored")
    static class NotAConsumer {}

    /** blank consumer name must throw */
    @BedrockConsumer(value = "", topic = "some-topic")
    static class BlankConsumerConsumer implements BedrockMessageConsumer {
        @Override public void consume(BedrockMessage message) {}
    }

    /** blank topic must throw */
    @BedrockConsumer(value = "some-consumer", topic = "")
    static class BlankTopicConsumer implements BedrockMessageConsumer {
        @Override public void consume(BedrockMessage message) {}
    }

    @Test
    void init_registersAllBedrockMessageConsumerBeans() {
        Map<String, Object> beans = new HashMap<>();
        beans.put("orderConsumer", new OrderConsumer());
        beans.put("notifyConsumer", new NotifyConsumer());
        when(ctx.getBeansWithAnnotation(BedrockConsumer.class)).thenReturn(beans);

        BedrockConsumerRegistry registry = new BedrockConsumerRegistry(ctx, subscriptionMapper);
        registry.init();

        assertThat(registry.getConsumer("order", "order")).isInstanceOf(OrderConsumer.class);
        assertThat(registry.getConsumer("notify", "notify")).isInstanceOf(NotifyConsumer.class);
    }

    @Test
    void init_upsertsSingleSubscriptionPerConsumer() {
        Map<String, Object> beans = new HashMap<>();
        beans.put("orderConsumer", new OrderConsumer());
        when(ctx.getBeansWithAnnotation(BedrockConsumer.class)).thenReturn(beans);

        BedrockConsumerRegistry registry = new BedrockConsumerRegistry(ctx, subscriptionMapper);
        registry.init();

        verify(subscriptionMapper).upsert("order", "order", 3);
    }

    @Test
    void init_resolvesTopicFromAnnotationWhenExplicitlySet() {
        Map<String, Object> beans = new HashMap<>();
        beans.put("inventoryConsumer", new InventoryConsumer());
        when(ctx.getBeansWithAnnotation(BedrockConsumer.class)).thenReturn(beans);

        BedrockConsumerRegistry registry = new BedrockConsumerRegistry(ctx, subscriptionMapper);
        registry.init();

        assertThat(registry.getConsumer("order-created", "inventory")).isInstanceOf(InventoryConsumer.class);
        verify(subscriptionMapper).upsert("order-created", "inventory", 3);
    }

    @Test
    void getConsumer_returnsNullForUnregisteredTopicConsumerPair() {
        when(ctx.getBeansWithAnnotation(BedrockConsumer.class)).thenReturn(new HashMap<>());

        BedrockConsumerRegistry registry = new BedrockConsumerRegistry(ctx, subscriptionMapper);
        registry.init();

        assertThat(registry.getConsumer("unknown", "consumer")).isNull();
    }

    @Test
    void init_ignoresBeansThatDoNotImplementBedrockMessageConsumer() {
        Map<String, Object> beans = new HashMap<>();
        beans.put("notAConsumer", new NotAConsumer());
        when(ctx.getBeansWithAnnotation(BedrockConsumer.class)).thenReturn(beans);

        BedrockConsumerRegistry registry = new BedrockConsumerRegistry(ctx, subscriptionMapper);
        registry.init();

        assertThat(registry.getConsumer("ignored", "ignored")).isNull();
        verify(subscriptionMapper, never()).upsert(any(), any(), anyInt());
    }

    @Test
    void init_registersNothingWhenContextHasNoAnnotatedBeans() {
        when(ctx.getBeansWithAnnotation(BedrockConsumer.class)).thenReturn(new HashMap<>());

        BedrockConsumerRegistry registry = new BedrockConsumerRegistry(ctx, subscriptionMapper);
        registry.init();

        assertThat(registry.getRegisteredKeys()).isEmpty();
    }

    @Test
    void init_throwsWhenConsumerNameIsBlank() {
        Map<String, Object> beans = new HashMap<>();
        beans.put("blankConsumer", new BlankConsumerConsumer());
        when(ctx.getBeansWithAnnotation(BedrockConsumer.class)).thenReturn(beans);

        BedrockConsumerRegistry registry = new BedrockConsumerRegistry(ctx, subscriptionMapper);

        assertThatThrownBy(registry::init)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("empty topic or consumer");
    }

    @Test
    void init_throwsWhenTopicIsBlank() {
        Map<String, Object> beans = new HashMap<>();
        beans.put("blankTopicConsumer", new BlankTopicConsumer());
        when(ctx.getBeansWithAnnotation(BedrockConsumer.class)).thenReturn(beans);

        BedrockConsumerRegistry registry = new BedrockConsumerRegistry(ctx, subscriptionMapper);

        assertThatThrownBy(registry::init)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("empty topic or consumer");
    }

    @Test
    void init_throwsWhenTopicConsumerPairIsDuplicated() {
        Map<String, Object> beans = new HashMap<>();
        beans.put("orderConsumer", new OrderConsumer());
        beans.put("duplicateOrderConsumer", new DuplicateOrderConsumer());
        when(ctx.getBeansWithAnnotation(BedrockConsumer.class)).thenReturn(beans);

        BedrockConsumerRegistry registry = new BedrockConsumerRegistry(ctx, subscriptionMapper);

        assertThatThrownBy(registry::init)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate @BedrockConsumer registration");
    }

    @Test
    void init_resolvesAnnotationThroughSpringJdkProxy() {
        // JDK proxy: proxy class itself does not carry @BedrockConsumer, but
        // AopUtils.getTargetClass() unwraps it to OrderConsumer where the annotation lives.
        OrderConsumer target = new OrderConsumer();
        ProxyFactory pf = new ProxyFactory(target);
        pf.addInterface(BedrockMessageConsumer.class);
        BedrockMessageConsumer jdkProxy = (BedrockMessageConsumer) pf.getProxy();

        assertThat(jdkProxy.getClass().getAnnotation(BedrockConsumer.class)).isNull();

        Map<String, Object> beans = new HashMap<>();
        beans.put("orderConsumer", jdkProxy);
        when(ctx.getBeansWithAnnotation(BedrockConsumer.class)).thenReturn(beans);

        BedrockConsumerRegistry registry = new BedrockConsumerRegistry(ctx, subscriptionMapper);
        registry.init();

        assertThat(registry.getConsumer("order", "order")).isNotNull();
        verify(subscriptionMapper).upsert("order", "order", 3);
    }
}
