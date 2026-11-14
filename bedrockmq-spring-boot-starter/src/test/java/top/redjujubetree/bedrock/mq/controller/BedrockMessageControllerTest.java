package top.redjujubetree.bedrock.mq.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import top.redjujubetree.bedrock.mq.client.EncryptedMessage;
import top.redjujubetree.bedrock.mq.client.MessageCipher;
import top.redjujubetree.bedrock.mq.config.BedrockMqProperties;
import top.redjujubetree.bedrock.mq.producer.MessageProducer;

import java.util.Base64;
import java.util.Collections;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class BedrockMessageControllerTest {
    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

    @Test
    void decryptsAndPublishesThroughStarterProducer() throws Exception {
        MessageProducer producer = mock(MessageProducer.class);
        when(producer.send(eq("orders"), eq("test"), eq("{}"), eq(0))).thenReturn(42L);
        ObjectMapper mapper = new ObjectMapper();
        MockMvc mvc = mvc(producer, mapper);
        EncryptedMessage body = new MessageCipher(KEY, "AES-256-GCM")
                .encrypt("{\"topic\":\"orders\",\"messageSource\":\"test\",\"payload\":\"{}\"}");
        body.setClient("orders");
        mvc.perform(post("/bedrock/v1/messages").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(body)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(42));
        verify(producer).send("orders", "test", "{}", 0);
    }

    @Test
    void rejectsUnknownClientAndInvalidCiphertext() throws Exception {
        MessageProducer producer = mock(MessageProducer.class);
        MockMvc mvc = mvc(producer, new ObjectMapper());
        mvc.perform(post("/bedrock/v1/messages").contentType(MediaType.APPLICATION_JSON)
                .content("{\"client\":\"unknown\",\"iv\":\"bad\",\"ciphertext\":\"bad\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/bedrock/v1/messages").contentType(MediaType.APPLICATION_JSON)
                .content("{\"client\":\"orders\",\"iv\":\"bad\",\"ciphertext\":\"bad\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(producer);
    }

    @Test
    void rejectsRequestEncryptedWithAnotherKey() throws Exception {
        MessageProducer producer = mock(MessageProducer.class);
        ObjectMapper mapper = new ObjectMapper();
        MockMvc mvc = mvc(producer, mapper);
        String wrongKey = Base64.getEncoder().encodeToString(new byte[16]);
        EncryptedMessage body = new MessageCipher(wrongKey, "AES-128-GCM")
                .encrypt("{\"topic\":\"orders\",\"messageSource\":\"test\",\"payload\":\"{}\"}");
        body.setClient("orders");
        mvc.perform(post("/bedrock/v1/messages").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(body)))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(producer);
    }

    @Test
    void decryptsWithServerKeyWhenClientHasNoIndividualConfig() throws Exception {
        MessageProducer producer = mock(MessageProducer.class);
        when(producer.send("orders", "test", "{}", 0)).thenReturn(43L);
        ObjectMapper mapper = new ObjectMapper();
        BedrockMqProperties.Server settings = new BedrockMqProperties.Server();
        settings.setEncryptionKey(KEY);
        MockMvc mvc = mvc(settings, producer, mapper);
        EncryptedMessage body = new MessageCipher(KEY, "AES-256-GCM")
                .encrypt("{\"topic\":\"orders\",\"messageSource\":\"test\",\"payload\":\"{}\"}");
        body.setClient("unconfigured-client");
        mvc.perform(post("/bedrock/v1/messages").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(body)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(43));
    }

    @Test
    void individualKeyOverridesServerKey() throws Exception {
        MessageProducer producer = mock(MessageProducer.class);
        when(producer.send("orders", "test", "{}", 0)).thenReturn(44L);
        ObjectMapper mapper = new ObjectMapper();
        BedrockMqProperties.Server settings = new BedrockMqProperties.Server();
        settings.setEncryptionKey(KEY);
        String individualKey = Base64.getEncoder().encodeToString(new byte[16]);
        BedrockMqProperties.Credential credential = new BedrockMqProperties.Credential();
        credential.setEncryptionKey(individualKey);
        credential.setAlgorithm("AES-128-GCM");
        settings.setClients(Collections.singletonMap("special", credential));
        MockMvc mvc = mvc(settings, producer, mapper);
        EncryptedMessage body = new MessageCipher(individualKey, "AES-128-GCM")
                .encrypt("{\"topic\":\"orders\",\"messageSource\":\"test\",\"payload\":\"{}\"}");
        body.setClient("special");
        mvc.perform(post("/bedrock/v1/messages").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(body)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(44));
        EncryptedMessage wrongForSpecial = new MessageCipher(KEY, "AES-256-GCM")
                .encrypt("{\"topic\":\"orders\",\"messageSource\":\"test\",\"payload\":\"{}\"}");
        wrongForSpecial.setClient("special");
        mvc.perform(post("/bedrock/v1/messages").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(wrongForSpecial)))
                .andExpect(status().isBadRequest());
        verify(producer).send("orders", "test", "{}", 0);
    }

    private MockMvc mvc(MessageProducer producer, ObjectMapper mapper) {
        BedrockMqProperties.Server settings = new BedrockMqProperties.Server();
        BedrockMqProperties.Credential client = new BedrockMqProperties.Credential();
        client.setEncryptionKey(KEY);
        settings.setClients(Collections.singletonMap("orders", client));
        return mvc(settings, producer, mapper);
    }

    private MockMvc mvc(BedrockMqProperties.Server settings, MessageProducer producer, ObjectMapper mapper) {
        return MockMvcBuilders.standaloneSetup(new BedrockMessageController(settings, mapper, producer)).build();
    }
}
