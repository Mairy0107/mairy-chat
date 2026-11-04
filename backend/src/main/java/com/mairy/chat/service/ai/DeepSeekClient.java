package com.mairy.chat.service.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mairy.chat.config.AiProperties;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** 薄 HTTP 客户端只处理 DeepSeek 协议，不记录请求、响应或上游错误正文。 */
@Component
public class DeepSeekClient {
    private final AiProperties properties;
    private final ObjectMapper json;
    private final RestClient client;

    /** 复用 Spring MVC 已有 RestClient，设置有限连接与读取等待。 */
    public DeepSeekClient(AiProperties properties, ObjectMapper json) {
        this.properties = properties;
        this.json = json;
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(Duration.ofSeconds(45));
        client = RestClient.builder().requestFactory(factory).build();
    }

    /** 分块读取 SSE 并即时转发完整 data 帧，必须收到 DONE 才认为回复完整。 */
    public void stream(List<Map<String, String>> messages, DeltaConsumer consumer) throws IOException {
        String base = properties.getBaseUrl().replaceAll("/+$", "");
        long deadline = System.nanoTime() + Duration.ofSeconds(120).toNanos();
        client.post().uri(base + "/chat/completions")
                .headers(headers -> headers.setBearerAuth(properties.getApiKey()))
                .contentType(MediaType.APPLICATION_JSON).accept(MediaType.TEXT_EVENT_STREAM)
                .body(Map.of("model", properties.getModel(), "messages", messages, "stream", true,
                        "temperature", 0.3, "max_tokens", 2048))
                .exchange((request, response) -> {
                    if (!response.getStatusCode().is2xxSuccessful()) throw new IOException("上游请求失败");
                    try (var reader = new InputStreamReader(response.getBody(), StandardCharsets.UTF_8)) {
                        readStream(reader, deadline, consumer);
                    }
                    return null;
                });
    }

    /** 批量解码 UTF-8，跨块保留半行；完整帧立即交付，不等待缓冲区填满。 */
    private void readStream(InputStreamReader reader, long deadline, DeltaConsumer consumer) throws IOException {
        char[] chunk = new char[4096];
        StringBuilder line = new StringBuilder();
        StringBuilder data = new StringBuilder();
        int count;
        while ((count = reader.read(chunk)) != -1) {
            if (System.nanoTime() > deadline) throw new IOException("上游回复超时");
            for (int index = 0; index < count; index++) {
                char value = chunk[index];
                if (value == '\n') {
                    if (consumeLine(line.toString(), data, consumer)) return;
                    line.setLength(0);
                } else if (value != '\r') {
                    line.append(value);
                    // 在拼接时限制单行大小，异常上游不能无限占用内存。
                    if (line.length() > 65536) throw new IOException("上游分片过大");
                }
            }
        }
        if (!line.isEmpty() && consumeLine(line.toString(), data, consumer)) return;
        if (!consume(data.toString(), consumer)) throw new IOException("上游回复中断");
    }

    /** 沿用空行分帧和 data 大小限制，兼容 CRLF、注释及末尾无换行。 */
    private boolean consumeLine(String line, StringBuilder data, DeltaConsumer consumer) throws IOException {
        if (line.isEmpty()) {
            boolean done = consume(data.toString(), consumer);
            data.setLength(0);
            return done;
        }
        if (line.startsWith("data:")) {
            data.append(line.substring(5).stripLeading());
            if (data.length() > 65536) throw new IOException("上游分片过大");
        }
        return false;
    }

    /** 只转发普通文本，不暴露 reasoning、工具调用或原始协议内容。 */
    private boolean consume(String data, DeltaConsumer consumer) throws IOException {
        if (data.isEmpty()) return false;
        if ("[DONE]".equals(data)) return true;
        var payload = json.readTree(data);
        if (payload.has("error")) throw new IOException("上游请求失败");
        var content = payload.path("choices").path(0).path("delta").path("content");
        if (content.isTextual() && !content.asText().isEmpty()) consumer.accept(content.asText());
        return false;
    }

    /** 回调允许下游断连中止上游读取并关闭连接。 */
    @FunctionalInterface
    public interface DeltaConsumer {
        void accept(String content) throws IOException;
    }
}
