package top.redjujubetree.bedrock.mq.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import top.redjujubetree.bedrock.mq.config.BedrockHttpClientProperties;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BedrockHttpClientTest {
    private static final String KEY_256 = Base64.getEncoder().encodeToString(new byte[32]);
    private static final String KEY_128 = Base64.getEncoder().encodeToString(new byte[16]);

    @Test
    void usesEachClientsAddressKeyAndAlgorithm() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        BedrockHttpClientProperties settings = new BedrockHttpClientProperties();
        Map<String, BedrockHttpClientProperties.Client> clients = new HashMap<>();
        clients.put("one", config(9527, KEY_256, "AES-256-GCM"));
        clients.put("two", config(9528, KEY_128, "AES-128-GCM"));
        settings.setClients(clients);

        Map<String, ByteArrayOutputStream> bodies = new HashMap<>();
        BedrockHttpClient client = new BedrockHttpClient(settings, mapper) {
            @Override
            protected HttpURLConnection openConnection(URL url) throws IOException {
                HttpURLConnection connection = mock(HttpURLConnection.class);
                ByteArrayOutputStream body = new ByteArrayOutputStream();
                bodies.put(url.toString(), body);
                when(connection.getOutputStream()).thenReturn(body);
                when(connection.getResponseCode()).thenReturn(200);
                when(connection.getInputStream()).thenReturn(new ByteArrayInputStream(
                        "{\"id\":\"42\"}".getBytes(StandardCharsets.UTF_8)));
                return connection;
            }
        };

        assertThat(client.send("one", "orders", "test", "{\"id\":1}")).isEqualTo(42L);
        assertThat(client.send("two", "orders", "test", "{\"id\":2}")).isEqualTo(42L);
        assertPayload(mapper, bodies.get("http://127.0.0.1:9527/bedrock/v1/messages"),
                "one", KEY_256, "AES-256-GCM", "{\"id\":1}");
        assertPayload(mapper, bodies.get("http://127.0.0.1:9528/bedrock/v1/messages"),
                "two", KEY_128, "AES-128-GCM", "{\"id\":2}");
        assertThatThrownBy(() -> client.send("missing", "orders", "test", "{}"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.send("one", "orders", "test", "  "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsTamperedCiphertextAndInvalidAlgorithm() {
        MessageCipher cipher = new MessageCipher(KEY_256, "AES-256-GCM");
        EncryptedMessage encrypted = cipher.encrypt("secret");
        encrypted.setCiphertext(Base64.getEncoder().encodeToString(new byte[20]));
        assertThatThrownBy(() -> cipher.decrypt(encrypted)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MessageCipher("bad", "AES-256-GCM"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MessageCipher(KEY_256, "DES"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private void assertPayload(ObjectMapper mapper, ByteArrayOutputStream body, String name,
                               String key, String algorithm, String expectedPayload) throws Exception {
        assertThat(body).isNotNull();
        EncryptedMessage encrypted = mapper.readValue(body.toByteArray(), EncryptedMessage.class);
        assertThat(encrypted.getClient()).isEqualTo(name);
        HttpMessageRequest request = mapper.readValue(
                new MessageCipher(key, algorithm).decrypt(encrypted), HttpMessageRequest.class);
        assertThat(request.getPayload()).isEqualTo(expectedPayload);
    }

    private BedrockHttpClientProperties.Client config(int port, String key, String algorithm) {
        BedrockHttpClientProperties.Client config = new BedrockHttpClientProperties.Client();
        config.setAddress("http://127.0.0.1");
        config.setPort(port);
        config.setEncryptionKey(key);
        config.setAlgorithm(algorithm);
        return config;
    }
}
