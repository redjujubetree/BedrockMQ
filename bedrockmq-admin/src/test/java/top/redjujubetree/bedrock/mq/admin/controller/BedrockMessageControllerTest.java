package top.redjujubetree.bedrock.mq.admin.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import top.redjujubetree.bedrock.mq.constant.MessageStatus;
import top.redjujubetree.bedrock.mq.entity.BedrockConsumeRecord;
import top.redjujubetree.bedrock.mq.entity.BedrockMessage;
import top.redjujubetree.bedrock.mq.mapper.BedrockConsumeRecordMapper;
import top.redjujubetree.bedrock.mq.mapper.BedrockMessageMapper;
import top.redjujubetree.bedrock.mq.recovery.TimeoutRecoveryTask;

import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class BedrockMessageControllerTest {

    @Autowired MockMvc mvc;
    @Autowired BedrockMessageMapper messageMapper;
    @Autowired BedrockConsumeRecordMapper consumeRecordMapper;
    @Autowired ObjectMapper objectMapper;
    @MockBean TimeoutRecoveryTask timeoutRecoveryTask;

    @BeforeEach
    void clearTables() {
        consumeRecordMapper.deleteAll();
        messageMapper.deleteAll();
    }

    // ── 生产消息 ──────────────────────────────────────────────────────────────────

    @Test
    void send_persistsMessageAndReturnsGeneratedId() throws Exception {
        String body = "{\"topic\":\"order\",\"messageSource\":\"shop\",\"payload\":\"{\\\"id\\\":1}\"}";

        MvcResult result = mvc.perform(post("/bedrock/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isString())
                .andReturn();

        Long id = extractId(result);
        // id returned is the message id; verify bedrock_message record exists
        BedrockMessage saved = messageMapper.selectById(id);
        assertThat(saved).isNotNull();
        assertThat(saved.getTopic()).isEqualTo("order");
        assertThat(saved.getMessageSource()).isEqualTo("shop");
        assertThat(saved.getPayload()).isEqualTo("{\"id\":1}");
        assertThat(saved.getCreatedAt()).isNotNull();
    }

    @Test
    void send_returnsBadRequestWhenTopicIsMissing() throws Exception {
        String body = "{\"payload\":\"{}\"}";

        mvc.perform(post("/bedrock/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    // ── 消费记录（重试） ───────────────────────────────────────────────────────────

    @Test
    void retry_resetsFailedRecordToPendingAndClearsErrorInfo() throws Exception {
        Long recordId = insertFailedConsumeRecord();

        mvc.perform(post("/bedrock/messages/{id}/retry", recordId))
                .andExpect(status().isOk());

        BedrockConsumeRecord updated = consumeRecordMapper.selectById(recordId);
        assertThat(updated.getStatus()).isEqualTo(MessageStatus.PENDING);
        assertThat(updated.getRetryCount()).isEqualTo(0);
        assertThat(updated.getErrorMsg()).isNull();
        assertThat(updated.getNodeId()).isNull();
    }

    @Test
    void retry_returnsNotFoundWhenRecordDoesNotExist() throws Exception {
        mvc.perform(post("/bedrock/messages/{id}/retry", 999999L))
                .andExpect(status().isNotFound());
    }

    // ── 软删除 ────────────────────────────────────────────────────────────────────

    @Test
    void delete_softDeletesRecordAndHidesItFromList() throws Exception {
        Long recordId = insertFailedConsumeRecord();

        mvc.perform(post("/bedrock/messages/{id}/delete", recordId))
                .andExpect(status().isOk());

        // selectByIdWithMessage filters deleted=0, so result should be null
        BedrockConsumeRecord found = consumeRecordMapper.selectByIdWithMessage(recordId);
        assertThat(found).isNull();

        // underlying row still exists in DB (soft delete, not physical)
        BedrockConsumeRecord raw = consumeRecordMapper.selectById(recordId);
        assertThat(raw).isNotNull();
        assertThat(raw.getDeleted()).isEqualTo(1);
    }

    @Test
    void delete_deletedRecordIsExcludedFromPendingPolling() throws Exception {
        Long recordId = insertPendingConsumeRecord();

        mvc.perform(post("/bedrock/messages/{id}/delete", recordId))
                .andExpect(status().isOk());

        // selectPending should not return deleted records
        java.util.List<BedrockConsumeRecord> pending =
                consumeRecordMapper.selectPending("order", "order", 100);
        assertThat(pending).noneMatch(r -> r.getId().equals(recordId));
    }

    // ── helpers ───────────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private Long extractId(MvcResult result) throws Exception {
        Map<String, Object> map = objectMapper.readValue(
                result.getResponse().getContentAsString(), Map.class);
        return Long.parseLong(map.get("id").toString());
    }

    private BedrockMessage insertMessage() {
        BedrockMessage msg = new BedrockMessage();
        msg.setTopic("order");
        msg.setMessageSource("shop");
        msg.setPayload("{\"id\":1}");
        msg.setCreatedAt(LocalDateTime.now());
        msg.setUpdatedAt(LocalDateTime.now());
        messageMapper.insert(msg);
        return msg;
    }

    private Long insertPendingConsumeRecord() {
        BedrockMessage msg = insertMessage();
        BedrockConsumeRecord record = new BedrockConsumeRecord();
        record.setMessageId(msg.getId());
        record.setTopic("order");
        record.setConsumer("order");
        record.setStatus(MessageStatus.PENDING);
        record.setRetryCount(0);
        record.setMaxRetry(3);
        record.setScheduledAt(LocalDateTime.now().minusSeconds(1));
        record.setCreatedAt(LocalDateTime.now());
        record.setUpdatedAt(LocalDateTime.now());
        consumeRecordMapper.insert(record);
        return record.getId();
    }

    private Long insertFailedConsumeRecord() {
        BedrockMessage msg = insertMessage();

        BedrockConsumeRecord record = new BedrockConsumeRecord();
        record.setMessageId(msg.getId());
        record.setTopic("order");
        record.setConsumer("order");
        record.setStatus(MessageStatus.FAILED);
        record.setRetryCount(3);
        record.setMaxRetry(3);
        record.setErrorMsg("processing failed");
        record.setNodeId("node-1");
        record.setScheduledAt(LocalDateTime.now());
        record.setCreatedAt(LocalDateTime.now());
        record.setUpdatedAt(LocalDateTime.now());
        consumeRecordMapper.insert(record);
        return record.getId();
    }
}
