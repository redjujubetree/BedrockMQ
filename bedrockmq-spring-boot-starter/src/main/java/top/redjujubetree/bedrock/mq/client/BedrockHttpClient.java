package top.redjujubetree.bedrock.mq.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import top.redjujubetree.bedrock.mq.config.BedrockHttpClientProperties;
import top.redjujubetree.bedrock.mq.producer.BedrockMessageRequest;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

/** Sends messages through a selected, independently configured HTTP client. */
public class BedrockHttpClient {
    private final Map<String, Endpoint> endpoints = new HashMap<>();
    private final ObjectMapper mapper;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;

    private static class Endpoint {
        private final URL url;
        private final MessageCipher cipher;
        private Endpoint(URL url, MessageCipher cipher) {
            this.url = url;
            this.cipher = cipher;
        }
    }

    public BedrockHttpClient(BedrockHttpClientProperties settings, ObjectMapper mapper) {
        this.mapper = mapper;
        this.connectTimeoutMs = settings.getConnectTimeoutMs();
        this.readTimeoutMs = settings.getReadTimeoutMs();
        if (connectTimeoutMs <= 0 || readTimeoutMs <= 0) {
            throw new IllegalArgumentException("HTTP timeouts must be positive");
        }
        if (settings.getClients() != null) {
            for (Map.Entry<String, BedrockHttpClientProperties.Client> entry : settings.getClients().entrySet()) {
                BedrockHttpClientProperties.Client config = entry.getValue();
                if (entry.getKey() == null || entry.getKey().trim().isEmpty()
                        || config == null || config.getAddress() == null || config.getAddress().trim().isEmpty()
                        || config.getPort() < 1 || config.getPort() > 65535
                        || config.getPath() == null || !config.getPath().startsWith("/")) {
                    throw new IllegalArgumentException("Invalid HTTP client configuration");
                }
                try {
                    URL base = new URL(config.getAddress());
                    if ((!"http".equals(base.getProtocol()) && !"https".equals(base.getProtocol()))
                            || base.getAuthority().contains("@") || base.getQuery() != null || base.getRef() != null
                            || !("/".equals(base.getPath()) || "".equals(base.getPath()))) {
                        throw new IllegalArgumentException("HTTP client address must be an http(s) origin");
                    }
                    URL url = new URL(base.getProtocol(), base.getHost(), config.getPort(), config.getPath());
                    endpoints.put(entry.getKey(), new Endpoint(url,
                            new MessageCipher(config.getEncryptionKey(), config.getAlgorithm())));
                } catch (IOException e) {
                    throw new IllegalArgumentException("Invalid HTTP client address", e);
                }
            }
        }
    }

    public Long send(String client, String topic, String messageSource, Object payload) throws IOException {
        return send(client, new BedrockMessageRequest(topic, messageSource, payload));
    }

    public Long send(String client, BedrockMessageRequest request) throws IOException {
        Endpoint endpoint = endpoints.get(client);
        if (endpoint == null) {
            throw new IllegalArgumentException("Unknown HTTP client: " + client);
        }
        if (request == null || request.getTopic() == null || request.getTopic().trim().isEmpty()
                || request.getMessageSource() == null || request.getMessageSource().trim().isEmpty()
                || request.getPayload() == null
                || (request.getPayload() instanceof String && ((String) request.getPayload()).trim().isEmpty())
                || request.getMaxRetry() < 0) {
            throw new IllegalArgumentException("topic, messageSource, nonblank payload and nonnegative maxRetry are required");
        }
        HttpMessageRequest wire = new HttpMessageRequest();
        wire.setTopic(request.getTopic());
        wire.setMessageSource(request.getMessageSource());
        wire.setPayload(request.getPayload() instanceof String ? (String) request.getPayload()
                : mapper.writeValueAsString(request.getPayload()));
        wire.setMaxRetry(request.getMaxRetry());
        wire.setScheduledAt(request.getScheduledAt() == null ? null : request.getScheduledAt().toString());
        EncryptedMessage encrypted = endpoint.cipher.encrypt(mapper.writeValueAsString(wire));
        encrypted.setClient(client);
        byte[] body = mapper.writeValueAsBytes(encrypted);
        HttpURLConnection connection = openConnection(endpoint.url);
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(connectTimeoutMs);
        connection.setReadTimeout(readTimeoutMs);
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        try {
            try (OutputStream output = connection.getOutputStream()) {
                output.write(body);
            }
            int status = connection.getResponseCode();
            if (status != 200) {
                throw new IOException("BedrockMQ HTTP endpoint returned status " + status);
            }
            try (InputStream input = connection.getInputStream()) {
                JsonNode response = mapper.readTree(input);
                if (response == null || !response.hasNonNull("id")) {
                    throw new IOException("BedrockMQ HTTP response has no message id");
                }
                return Long.valueOf(response.get("id").asText());
            }
        } finally {
            connection.disconnect();
        }
    }

    protected HttpURLConnection openConnection(URL url) throws IOException {
        return (HttpURLConnection) url.openConnection();
    }
}
