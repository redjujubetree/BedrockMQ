package top.redjujubetree.bedrock.mq.admin.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import top.redjujubetree.bedrock.mq.admin.dto.MessageSendRequest;
import top.redjujubetree.bedrock.mq.admin.dto.PageResult;
import top.redjujubetree.bedrock.mq.entity.BedrockConsumeRecord;
import top.redjujubetree.bedrock.mq.entity.BedrockSubscription;
import top.redjujubetree.bedrock.mq.mapper.BedrockConsumeRecordMapper;
import top.redjujubetree.bedrock.mq.mapper.BedrockSubscriptionMapper;
import top.redjujubetree.bedrock.mq.producer.MessageProducer;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BedrockAdminServiceTest {

    @Mock BedrockConsumeRecordMapper consumeRecordMapper;
    @Mock BedrockSubscriptionMapper subscriptionMapper;
    @Mock MessageProducer messageProducer;

    BedrockAdminService service;

    @BeforeEach
    void setUp() {
        service = new BedrockAdminService(consumeRecordMapper, subscriptionMapper, messageProducer);
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

    // ── cancel ────────────────────────────────────────────────────────────────────

    @Test
    void cancel_returnsTrueWhenPendingRecordIsCancelled() {
        when(consumeRecordMapper.cancelPending(eq(1L), any(LocalDateTime.class))).thenReturn(1);
        assertThat(service.cancel(1L)).isTrue();
    }

    @Test
    void cancel_returnsFalseWhenRecordIsNotFoundOrNotPending() {
        when(consumeRecordMapper.cancelPending(eq(999L), any(LocalDateTime.class))).thenReturn(0);
        assertThat(service.cancel(999L)).isFalse();
    }

    // ── updateMaxRetry ────────────────────────────────────────────────────────────

    @Test
    void updateMaxRetry_returnsTrueOnSuccess() {
        when(consumeRecordMapper.updateMaxRetry(1L, 5)).thenReturn(1);
        assertThat(service.updateMaxRetry(1L, 5)).isTrue();
    }

    @Test
    void updateMaxRetry_returnsFalseWhenRecordNotFound() {
        when(consumeRecordMapper.updateMaxRetry(999L, 5)).thenReturn(0);
        assertThat(service.updateMaxRetry(999L, 5)).isFalse();
    }

    // ── batchUpdateMaxRetry ───────────────────────────────────────────────────────

    @Test
    void batchUpdateMaxRetry_returnsZeroForEmptyIds() {
        assertThat(service.batchUpdateMaxRetry(Collections.emptyList(), 3)).isEqualTo(0);
        verify(consumeRecordMapper, never()).batchUpdateMaxRetry(any(), anyInt());
    }

    @Test
    void batchUpdateMaxRetry_returnsZeroForNullIds() {
        assertThat(service.batchUpdateMaxRetry(null, 3)).isEqualTo(0);
        verify(consumeRecordMapper, never()).batchUpdateMaxRetry(any(), anyInt());
    }

    @Test
    void batchUpdateMaxRetry_returnsUpdatedCountOnSuccess() {
        List<Long> ids = Arrays.asList(1L, 2L);
        when(consumeRecordMapper.batchUpdateMaxRetry(ids, 5)).thenReturn(2);
        assertThat(service.batchUpdateMaxRetry(ids, 5)).isEqualTo(2);
    }

    // ── batchRetry ────────────────────────────────────────────────────────────────

    @Test
    void batchRetry_returnsZeroForEmptyIds() {
        assertThat(service.batchRetry(Collections.emptyList())).isEqualTo(0);
        verify(consumeRecordMapper, never()).batchResetToPending(any(), any());
    }

    @Test
    void batchRetry_returnsZeroForNullIds() {
        assertThat(service.batchRetry(null)).isEqualTo(0);
        verify(consumeRecordMapper, never()).batchResetToPending(any(), any());
    }

    @Test
    void batchRetry_returnsCountOfResetRecords() {
        List<Long> ids = Arrays.asList(1L, 2L, 3L);
        when(consumeRecordMapper.batchResetToPending(eq(ids), any(LocalDateTime.class))).thenReturn(3);
        assertThat(service.batchRetry(ids)).isEqualTo(3);
    }

    // ── listMessages ──────────────────────────────────────────────────────────────

    @Test
    void listMessages_returnsPageResultWithCorrectTotals() {
        List<BedrockConsumeRecord> records = Collections.singletonList(new BedrockConsumeRecord());
        when(consumeRecordMapper.countMessages("order", "order", 0)).thenReturn(1L);
        when(consumeRecordMapper.listMessages("order", "order", 0, 0L, 20L)).thenReturn(records);

        PageResult<BedrockConsumeRecord> result = service.listMessages("order", "order", 0, 1, 20);

        assertThat(result.getTotal()).isEqualTo(1L);
        assertThat(result.getRecords()).hasSize(1);
    }

    @Test
    void listMessages_computesOffsetCorrectly() {
        when(consumeRecordMapper.countMessages(null, null, null)).thenReturn(0L);
        when(consumeRecordMapper.listMessages(null, null, null, 40L, 20L))
                .thenReturn(Collections.emptyList());

        service.listMessages(null, null, null, 3, 20);

        verify(consumeRecordMapper).listMessages(null, null, null, 40L, 20L);
    }

    // ── getById ───────────────────────────────────────────────────────────────────

    @Test
    void getById_returnsRecordWithPayloadFromJoin() {
        BedrockConsumeRecord record = new BedrockConsumeRecord();
        record.setId(1L);
        record.setPayload("{\"id\":1}");
        when(consumeRecordMapper.selectByIdWithMessage(1L)).thenReturn(record);

        BedrockConsumeRecord result = service.getById(1L);

        assertThat(result.getPayload()).isEqualTo("{\"id\":1}");
    }

    @Test
    void getById_returnsNullWhenRecordNotFound() {
        when(consumeRecordMapper.selectByIdWithMessage(999L)).thenReturn(null);
        assertThat(service.getById(999L)).isNull();
    }

    // ── getStats ──────────────────────────────────────────────────────────────────

    @Test
    void getStats_delegatesToMapper() {
        List<Map<String, Object>> stats = Collections.singletonList(
                Collections.singletonMap("count", 5L));
        when(consumeRecordMapper.selectStatusCountByTopicAndConsumer()).thenReturn(stats);

        assertThat(service.getStats()).isEqualTo(stats);
    }

    // ── getRegisteredProcessors ───────────────────────────────────────────────────

    @Test
    void getRegisteredProcessors_returnsOnlyEnabledSubscriptionKeys() {
        BedrockSubscription enabled = new BedrockSubscription();
        enabled.setTopic("order");
        enabled.setConsumer("order");
        enabled.setStatus(1);

        BedrockSubscription disabled = new BedrockSubscription();
        disabled.setTopic("notify");
        disabled.setConsumer("notify");
        disabled.setStatus(0);

        when(subscriptionMapper.findAll()).thenReturn(Arrays.asList(enabled, disabled));

        assertThat(service.getRegisteredProcessors()).containsExactly("order:order");
    }

    @Test
    void getRegisteredProcessors_returnsEmptySetWhenNoEnabledSubscriptions() {
        when(subscriptionMapper.findAll()).thenReturn(Collections.emptyList());
        assertThat(service.getRegisteredProcessors()).isEmpty();
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
