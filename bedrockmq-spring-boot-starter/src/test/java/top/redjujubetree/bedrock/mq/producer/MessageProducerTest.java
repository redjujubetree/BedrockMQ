package top.redjujubetree.bedrock.mq.producer;

import com.fasterxml.jackson.databind.ObjectMapper;
import top.redjujubetree.bedrock.mq.entity.BedrockConsumeRecord;
import top.redjujubetree.bedrock.mq.entity.BedrockMessage;
import top.redjujubetree.bedrock.mq.entity.BedrockSubscription;
import top.redjujubetree.bedrock.mq.mapper.BedrockConsumeRecordMapper;
import top.redjujubetree.bedrock.mq.mapper.BedrockMessageMapper;
import top.redjujubetree.bedrock.mq.mapper.BedrockSubscriptionMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MessageProducerTest {

    @Mock BedrockMessageMapper messageMapper;
    @Mock BedrockConsumeRecordMapper consumeRecordMapper;
    @Mock BedrockSubscriptionMapper subscriptionMapper;

    MessageProducer producer;

    @BeforeEach
    void setUp() {
        producer = new MessageProducer(messageMapper, consumeRecordMapper, subscriptionMapper, new ObjectMapper());
    }

    // ── message insertion ───────────────────────────────────────────────────────

    @Test
    void send_insertsMessageWithTopicAndSource() {
        when(subscriptionMapper.findEnabledByTopic("order")).thenReturn(Collections.emptyList());

        producer.send("order", "shop", "payload");

        BedrockMessage inserted = captureInsertedMessage();
        assertThat(inserted.getTopic()).isEqualTo("order");
        assertThat(inserted.getMessageSource()).isEqualTo("shop");
        assertThat(inserted.getPayload()).isEqualTo("payload");
    }

    @Test
    void send_setsCreatedAtAndUpdatedAtExplicitly() {
        when(subscriptionMapper.findEnabledByTopic("order")).thenReturn(Collections.emptyList());
        LocalDateTime before = LocalDateTime.now().minusSeconds(1);

        producer.send("order", "shop", "payload");

        BedrockMessage inserted = captureInsertedMessage();
        assertThat(inserted.getCreatedAt()).isAfter(before);
        assertThat(inserted.getUpdatedAt()).isAfter(before);
    }

    // ── consume record fan-out ──────────────────────────────────────────────────

    @Test
    void send_createsNoConsumeRecordsWhenNoSubscriptionsExist() {
        when(subscriptionMapper.findEnabledByTopic("order")).thenReturn(Collections.emptyList());

        producer.send("order", "shop", "payload");

        verify(consumeRecordMapper, never()).insertBatch(any());
    }

    @Test
    void send_createsOneConsumeRecordPerEnabledSubscription() {
        BedrockSubscription sub1 = subscription("order", "order", 3);
        BedrockSubscription sub2 = subscription("order", "billing", 5);
        when(subscriptionMapper.findEnabledByTopic("order")).thenReturn(Arrays.asList(sub1, sub2));

        producer.send("order", "shop", "payload");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<BedrockConsumeRecord>> captor = ArgumentCaptor.forClass(List.class);
        verify(consumeRecordMapper).insertBatch(captor.capture());
        List<BedrockConsumeRecord> records = captor.getValue();

        assertThat(records).hasSize(2);
        assertThat(records.stream().map(BedrockConsumeRecord::getConsumer))
                .containsExactlyInAnyOrder("order", "billing");
    }

    @Test
    void send_usesSubscriptionMaxRetryWhenCallerProvidesZero() {
        BedrockSubscription sub = subscription("order", "order", 7);
        when(subscriptionMapper.findEnabledByTopic("order")).thenReturn(Collections.singletonList(sub));

        producer.send("order", "shop", "payload", 0);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<BedrockConsumeRecord>> captor = ArgumentCaptor.forClass(List.class);
        verify(consumeRecordMapper).insertBatch(captor.capture());
        assertThat(captor.getValue().get(0).getMaxRetry()).isEqualTo(7);
    }

    @Test
    void send_overridesSubscriptionMaxRetryWhenCallerProvidesNonZero() {
        BedrockSubscription sub = subscription("order", "order", 3);
        when(subscriptionMapper.findEnabledByTopic("order")).thenReturn(Collections.singletonList(sub));

        producer.send("order", "shop", "payload", 10);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<BedrockConsumeRecord>> captor = ArgumentCaptor.forClass(List.class);
        verify(consumeRecordMapper).insertBatch(captor.capture());
        assertThat(captor.getValue().get(0).getMaxRetry()).isEqualTo(10);
    }

    @Test
    void sendDelayed_setsScheduledAtInTheFuture() {
        BedrockSubscription sub = subscription("order", "order", 3);
        when(subscriptionMapper.findEnabledByTopic("order")).thenReturn(Collections.singletonList(sub));
        LocalDateTime before = LocalDateTime.now();

        producer.sendDelayed("order", "shop", "payload", Duration.ofHours(1));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<BedrockConsumeRecord>> captor = ArgumentCaptor.forClass(List.class);
        verify(consumeRecordMapper).insertBatch(captor.capture());
        assertThat(captor.getValue().get(0).getScheduledAt()).isAfter(before.plusMinutes(59));
    }

    @Test
    @SuppressWarnings("unchecked")
    void sendBatch_insertsAllMessagesAndCreatesConsumeRecordsPerSubscription() {
        BedrockSubscription sub = subscription("order", "order", 3);
        when(subscriptionMapper.findEnabledByTopic("order")).thenReturn(Collections.singletonList(sub));
        when(subscriptionMapper.findEnabledByTopic("notify")).thenReturn(Collections.emptyList());

        BedrockMessageRequest r1 = new BedrockMessageRequest("order", "shop-a", "p1");
        BedrockMessageRequest r2 = new BedrockMessageRequest("notify", "shop-b", "p2");
        producer.sendBatch(Arrays.asList(r1, r2));

        ArgumentCaptor<List<BedrockMessage>> msgCaptor = ArgumentCaptor.forClass(List.class);
        verify(messageMapper).insertBatch(msgCaptor.capture());
        assertThat(msgCaptor.getValue()).hasSize(2);

        ArgumentCaptor<List<BedrockConsumeRecord>> recordCaptor = ArgumentCaptor.forClass(List.class);
        verify(consumeRecordMapper).insertBatch(recordCaptor.capture());
        // only "order" has a subscription; "notify" has none
        assertThat(recordCaptor.getValue()).hasSize(1);
        assertThat(recordCaptor.getValue().get(0).getTopic()).isEqualTo("order");
    }

    // ── payload serialization ───────────────────────────────────────────────────

    @Test
    void toJson_passesStringPayloadThroughWithoutSerialization() {
        when(subscriptionMapper.findEnabledByTopic("order")).thenReturn(Collections.emptyList());

        producer.send("order", "test", "raw-json-string");

        assertThat(captureInsertedMessage().getPayload()).isEqualTo("raw-json-string");
    }

    @Test
    void toJson_serializesObjectPayloadToJson() {
        when(subscriptionMapper.findEnabledByTopic("order")).thenReturn(Collections.emptyList());

        producer.send("order", "test", new SamplePayload("123", 99));

        String payload = captureInsertedMessage().getPayload();
        assertThat(payload).contains("\"orderId\"");
        assertThat(payload).contains("\"amount\"");
    }

    @Test
    void toJson_throwsIllegalArgumentExceptionOnUnserializablePayload() {
        Object unserializable = new Object() {
            public final Object self = this;
        };

        assertThatThrownBy(() -> producer.send("order", "test", unserializable))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Failed to serialize payload");
    }

    // ── sendBatch: per-request overrides ───────────────────────────────────────

    @Test
    void sendBatch_usesPerRequestScheduledAt() {
        BedrockSubscription sub = subscription("order", "order", 3);
        when(subscriptionMapper.findEnabledByTopic("order")).thenReturn(Collections.singletonList(sub));

        LocalDateTime future = LocalDateTime.now().plusHours(2);
        BedrockMessageRequest req = new BedrockMessageRequest("order", "shop", "p1");
        req.setScheduledAt(future);

        producer.sendBatch(Collections.singletonList(req));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<BedrockConsumeRecord>> captor = ArgumentCaptor.forClass(List.class);
        verify(consumeRecordMapper).insertBatch(captor.capture());
        assertThat(captor.getValue().get(0).getScheduledAt()).isEqualTo(future);
    }

    @Test
    void sendBatch_overridesSubscriptionMaxRetryWhenPerRequestMaxRetryIsNonZero() {
        BedrockSubscription sub = subscription("order", "order", 3);
        when(subscriptionMapper.findEnabledByTopic("order")).thenReturn(Collections.singletonList(sub));

        BedrockMessageRequest req = new BedrockMessageRequest("order", "shop", "p1");
        req.setMaxRetry(9);

        producer.sendBatch(Collections.singletonList(req));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<BedrockConsumeRecord>> captor = ArgumentCaptor.forClass(List.class);
        verify(consumeRecordMapper).insertBatch(captor.capture());
        assertThat(captor.getValue().get(0).getMaxRetry()).isEqualTo(9);
    }

    @Test
    void sendBatch_doesNotCallConsumeRecordInsertWhenNoSubscriptionsExist() {
        when(subscriptionMapper.findEnabledByTopic("order")).thenReturn(Collections.emptyList());

        producer.sendBatch(Collections.singletonList(new BedrockMessageRequest("order", "shop", "p")));

        verify(consumeRecordMapper, never()).insertBatch(any());
    }

    // ── parameter validation ────────────────────────────────────────────────────

    @Test
    void send_throwsWhenTopicIsNull() {
        assertThatThrownBy(() -> producer.send(null, "shop", "payload"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("topic");
    }

    @Test
    void send_throwsWhenTopicIsEmpty() {
        assertThatThrownBy(() -> producer.send("", "shop", "payload"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("topic");
    }

    @Test
    void send_throwsWhenMessageSourceIsNull() {
        assertThatThrownBy(() -> producer.send("order", null, "payload"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("messageSource");
    }

    @Test
    void send_throwsWhenMessageSourceIsEmpty() {
        assertThatThrownBy(() -> producer.send("order", "", "payload"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("messageSource");
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    private BedrockMessage captureInsertedMessage() {
        ArgumentCaptor<BedrockMessage> captor = ArgumentCaptor.forClass(BedrockMessage.class);
        verify(messageMapper).insert(captor.capture());
        return captor.getValue();
    }

    private BedrockSubscription subscription(String topic, String consumer, int maxRetry) {
        BedrockSubscription s = new BedrockSubscription();
        s.setTopic(topic);
        s.setConsumer(consumer);
        s.setMaxRetry(maxRetry);
        s.setStatus(1);
        return s;
    }

    static class SamplePayload {
        public final String orderId;
        public final int amount;

        SamplePayload(String orderId, int amount) {
            this.orderId = orderId;
            this.amount = amount;
        }
    }
}
