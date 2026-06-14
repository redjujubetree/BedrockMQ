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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:sqlite:file::memory:?cache=shared",
        "spring.datasource.driver-class-name=org.sqlite.JDBC",
        "spring.datasource.username=",
        "spring.datasource.password=",
        "spring.datasource.hikari.maximum-pool-size=1",
        "spring.sql.init.schema-locations=classpath:schema-sqlite.sql",
        "spring.sql.init.mode=always",
        "bedrock.mq.db-dialect=sqlite"
})
@AutoConfigureMockMvc
class BedrockMessageControllerSqliteTest {

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

    // ── 列表查询 ──────────────────────────────────────────────────────────────────

    @Test
    void list_returnsPageResultWithRecords() throws Exception {
        insertFailedConsumeRecord();

        mvc.perform(get("/bedrock/messages")
                        .param("topic", "order")
                        .param("page", "1")
                        .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.records").isArray());
    }

    @Test
    void list_returnsEmptyPageWhenNoRecordsMatch() throws Exception {
        mvc.perform(get("/bedrock/messages")
                        .param("topic", "non-existent-topic"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));
    }

    // ── 单条查询 ──────────────────────────────────────────────────────────────────

    @Test
    void get_returnsRecordWithPayloadWhenFound() throws Exception {
        Long recordId = insertFailedConsumeRecord();

        mvc.perform(get("/bedrock/messages/{id}", recordId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isString())
                .andExpect(jsonPath("$.payload").value("{\"id\":1}"));
    }

    @Test
    void get_returnsNotFoundWhenRecordDoesNotExist() throws Exception {
        mvc.perform(get("/bedrock/messages/{id}", 999999L))
                .andExpect(status().isNotFound());
    }

    // ── 取消 ──────────────────────────────────────────────────────────────────────

    @Test
    void cancel_cancelsPendingRecordAndReturnsOk() throws Exception {
        Long recordId = insertPendingConsumeRecord();

        mvc.perform(post("/bedrock/messages/{id}/cancel", recordId))
                .andExpect(status().isOk());

        BedrockConsumeRecord updated = consumeRecordMapper.selectById(recordId);
        assertThat(updated.getStatus()).isEqualTo(MessageStatus.FAILED);
    }

    @Test
    void cancel_returnsNotFoundWhenRecordIsNotPendingOrDoesNotExist() throws Exception {
        Long recordId = insertFailedConsumeRecord();

        // already FAILED, cancelPending only acts on PENDING → 404
        mvc.perform(post("/bedrock/messages/{id}/cancel", recordId))
                .andExpect(status().isNotFound());
    }

    // ── 修改 maxRetry ─────────────────────────────────────────────────────────────

    @Test
    void updateMaxRetry_updatesValueAndReturnsOk() throws Exception {
        Long recordId = insertPendingConsumeRecord();
        String body = "{\"maxRetry\":10}";

        mvc.perform(post("/bedrock/messages/{id}/max-retry", recordId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        BedrockConsumeRecord updated = consumeRecordMapper.selectById(recordId);
        assertThat(updated.getMaxRetry()).isEqualTo(10);
    }

    @Test
    void updateMaxRetry_returnsBadRequestWhenValueIsNegative() throws Exception {
        Long recordId = insertPendingConsumeRecord();
        String body = "{\"maxRetry\":-1}";

        mvc.perform(post("/bedrock/messages/{id}/max-retry", recordId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void updateMaxRetry_returnsNotFoundWhenRecordDoesNotExist() throws Exception {
        mvc.perform(post("/bedrock/messages/{id}/max-retry", 999999L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"maxRetry\":5}"))
                .andExpect(status().isNotFound());
    }

    // ── 批量重试 ──────────────────────────────────────────────────────────────────

    @Test
    void batchRetry_resetsMultipleFailedRecordsAndReturnsCount() throws Exception {
        Long id1 = insertFailedConsumeRecord();
        Long id2 = insertFailedConsumeRecord();
        String body = "{\"ids\":[" + id1 + "," + id2 + "]}";

        mvc.perform(post("/bedrock/messages/batch/retry")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updated").value(2));

        assertThat(consumeRecordMapper.selectById(id1).getStatus()).isEqualTo(MessageStatus.PENDING);
        assertThat(consumeRecordMapper.selectById(id2).getStatus()).isEqualTo(MessageStatus.PENDING);
    }

    @Test
    void batchRetry_returnsBadRequestWhenIdsAreEmpty() throws Exception {
        mvc.perform(post("/bedrock/messages/batch/retry")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    // ── 批量修改 maxRetry ─────────────────────────────────────────────────────────

    @Test
    void batchUpdateMaxRetry_updatesMultipleRecordsAndReturnsCount() throws Exception {
        Long id1 = insertPendingConsumeRecord();
        Long id2 = insertPendingConsumeRecord();
        String body = "{\"ids\":[" + id1 + "," + id2 + "],\"maxRetry\":7}";

        mvc.perform(post("/bedrock/messages/batch/max-retry")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updated").value(2));

        assertThat(consumeRecordMapper.selectById(id1).getMaxRetry()).isEqualTo(7);
        assertThat(consumeRecordMapper.selectById(id2).getMaxRetry()).isEqualTo(7);
    }

    @Test
    void batchUpdateMaxRetry_returnsBadRequestWhenIdsAreEmpty() throws Exception {
        mvc.perform(post("/bedrock/messages/batch/max-retry")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[],\"maxRetry\":5}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void batchUpdateMaxRetry_returnsBadRequestWhenMaxRetryIsNegative() throws Exception {
        Long recordId = insertPendingConsumeRecord();
        String body = "{\"ids\":[" + recordId + "],\"maxRetry\":-1}";

        mvc.perform(post("/bedrock/messages/batch/max-retry")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    // ── 软删除 ────────────────────────────────────────────────────────────────────

    @Test
    void delete_softDeletesRecordAndHidesItFromList() throws Exception {
        Long recordId = insertFailedConsumeRecord();

        mvc.perform(post("/bedrock/messages/{id}/delete", recordId))
                .andExpect(status().isOk());

        BedrockConsumeRecord found = consumeRecordMapper.selectByIdWithMessage(recordId);
        assertThat(found).isNull();

        BedrockConsumeRecord raw = consumeRecordMapper.selectById(recordId);
        assertThat(raw).isNotNull();
        assertThat(raw.getDeleted()).isEqualTo(1);
    }

    @Test
    void delete_deletedRecordIsExcludedFromPendingPolling() throws Exception {
        Long recordId = insertPendingConsumeRecord();

        mvc.perform(post("/bedrock/messages/{id}/delete", recordId))
                .andExpect(status().isOk());

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
