package top.redjujubetree.bedrock.mq.admin.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import top.redjujubetree.bedrock.mq.admin.dto.MessageSendRequest;
import top.redjujubetree.bedrock.mq.mapper.BedrockConsumeRecordMapper;
import top.redjujubetree.bedrock.mq.mapper.BedrockSubscriptionMapper;
import top.redjujubetree.bedrock.mq.processor.ProcessorRegistry;
import top.redjujubetree.bedrock.mq.producer.MessageProducer;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BedrockAdminServiceTest {

    @Mock BedrockConsumeRecordMapper consumeRecordMapper;
    @Mock BedrockSubscriptionMapper subscriptionMapper;
    @Mock ProcessorRegistry processorRegistry;
    @Mock MessageProducer messageProducer;

    BedrockAdminService service;

    @BeforeEach
    void setUp() {
        service = new BedrockAdminService(consumeRecordMapper, subscriptionMapper, processorRegistry, messageProducer);
    }

    // ── 生产消息 ──────────────────────────────────────────────────────────────────

    @Test
    void send_callsProducerWithCorrectArgsAndReturnsId() {
        MessageSendRequest req = buildSendRequest("order", "shop", "{\"id\":1}", null, null);
        when(messageProducer.send("order", "shop", "{\"id\":1}", 0)).thenReturn(42L);

        Long id = service.send(req);

        assertThat(id).isEqualTo(42L);
        verify(messageProducer).send("order", "shop", "{\"id\":1}", 0);
        verify(messageProducer, never()).sendAt(any(), any(), any(), anyInt(), any());
    }

    @Test
    void send_usesZeroMaxRetryByDefaultDeferringToSubscriptionConfig() {
        MessageSendRequest req = buildSendRequest("order", "shop", "{}", null, null);
        when(messageProducer.send(eq("order"), eq("shop"), eq("{}"), eq(0))).thenReturn(1L);

        service.send(req);

        verify(messageProducer).send("order", "shop", "{}", 0);
    }

    @Test
    void send_usesProvidedMaxRetryWhenSet() {
        MessageSendRequest req = buildSendRequest("order", "shop", "{}", 5, null);
        when(messageProducer.send(eq("order"), eq("shop"), eq("{}"), eq(5))).thenReturn(1L);

        service.send(req);

        verify(messageProducer).send("order", "shop", "{}", 5);
    }

    @Test
    void send_callsSendAtAndSkipsImmediateSendWhenScheduledAtIsProvided() {
        MessageSendRequest req = buildSendRequest("order", "shop", "{}", null, "2099-12-31T10:00:00");
        when(messageProducer.sendAt(any(), any(), any(), anyInt(), any())).thenReturn(10L);

        Long id = service.send(req);

        assertThat(id).isEqualTo(10L);
        verify(messageProducer).sendAt(eq("order"), eq("shop"), eq("{}"), eq(0), any());
        verify(messageProducer, never()).send(any(), any(), any(), anyInt());
    }

    @Test
    void send_throwsIllegalArgumentExceptionWhenTopicIsBlank() {
        MessageSendRequest req = buildSendRequest("  ", "shop", "{}", null, null);

        assertThatThrownBy(() -> service.send(req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("topic");
    }

    @Test
    void send_throwsIllegalArgumentExceptionWhenPayloadIsBlank() {
        MessageSendRequest req = buildSendRequest("order", "shop", "", null, null);

        assertThatThrownBy(() -> service.send(req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("payload");
    }

    @Test
    void send_throwsIllegalArgumentExceptionWhenScheduledAtIsMalformed() {
        MessageSendRequest req = buildSendRequest("order", "shop", "{}", null, "not-a-date");

        assertThatThrownBy(() -> service.send(req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ISO-8601");
    }

    @Test
    void send_throwsIllegalArgumentExceptionWhenMessageSourceIsBlank() {
        MessageSendRequest req = buildSendRequest("order", "  ", "{}", null, null);

        assertThatThrownBy(() -> service.send(req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("messageSource");
    }

    @Test
    void send_throwsIllegalArgumentExceptionWhenMessageSourceIsNull() {
        MessageSendRequest req = buildSendRequest("order", null, "{}", null, null);

        assertThatThrownBy(() -> service.send(req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("messageSource");
    }

    // ── 消费记录操作 ──────────────────────────────────────────────────────────────

    @Test
    void retry_returnsTrueWhenFailedRecordIsResetToPending() {
        when(consumeRecordMapper.resetToPending(eq(1L), any(LocalDateTime.class))).thenReturn(1);

        boolean result = service.retry(1L);

        assertThat(result).isTrue();
        verify(consumeRecordMapper).resetToPending(eq(1L), any(LocalDateTime.class));
    }

    @Test
    void retry_returnsFalseWhenRecordIsNotFoundOrNotInFailedStatus() {
        when(consumeRecordMapper.resetToPending(eq(999L), any(LocalDateTime.class))).thenReturn(0);

        boolean result = service.retry(999L);

        assertThat(result).isFalse();
    }

    // ── 订阅管理 ─────────────────────────────────────────────────────────────────

    @Test
    void enableSubscription_returnsTrueOnSuccess() {
        when(subscriptionMapper.enable(1L)).thenReturn(1);
        assertThat(service.enableSubscription(1L)).isTrue();
    }

    @Test
    void disableSubscription_returnsTrueOnSuccess() {
        when(subscriptionMapper.disable(1L)).thenReturn(1);
        assertThat(service.disableSubscription(1L)).isTrue();
    }

    @Test
    void disableSubscription_returnsFalseWhenNotFound() {
        when(subscriptionMapper.disable(999L)).thenReturn(0);
        assertThat(service.disableSubscription(999L)).isFalse();
    }

    // ── helpers ───────────────────────────────────────────────────────────────────

    private MessageSendRequest buildSendRequest(String topic, String source, String payload,
                                                Integer maxRetry, String scheduledAt) {
        MessageSendRequest req = new MessageSendRequest();
        req.setTopic(topic);
        req.setMessageSource(source);
        req.setPayload(payload);
        req.setMaxRetry(maxRetry);
        req.setScheduledAt(scheduledAt);
        return req;
    }
}
