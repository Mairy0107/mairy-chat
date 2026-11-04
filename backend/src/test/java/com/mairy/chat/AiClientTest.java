package com.mairy.chat;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mairy.chat.config.AiProperties;
import com.mairy.chat.service.ai.DeepSeekClient;
import com.mairy.chat.service.ai.SensitiveWordFilter;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.core.io.InputStreamResource;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** 无网络的上游协议及敏感词边界回归。 */
class AiClientTest {
    /** 校验请求协议、忽略 reasoning，且完整流必须包含 DONE。 */
    @Test
    void clientParsesSseAndRejectsTruncation() throws Exception {
        var properties = new AiProperties();
        properties.setApiKey("test-placeholder");
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var client = new DeepSeekClient(properties, new ObjectMapper());
        ReflectionTestUtils.setField(client, "client", builder.build());
        server.expect(requestTo("https://api.deepseek.com/chat/completions"))
                .andExpect(header("Authorization", "Bearer test-placeholder"))
                .andExpect(jsonPath("$.stream").value(true)).andExpect(jsonPath("$.temperature").value(0.3))
                .andRespond(withSuccess(": keepalive\n\ndata: {\"choices\":[{\"delta\":{\"reasoning_content\":\"不可转发\"}}]}\n\n"
                        + "data: {\"choices\":[{\"delta\":{\"content\":\"你好\"}}]}\r\n\r\ndata: [DONE]\n\n", MediaType.TEXT_EVENT_STREAM));
        var text = new StringBuilder();
        client.stream(List.of(Map.of("role", "user", "content", "问候")), text::append);
        assertEquals("你好", text.toString());
        server.verify();
        server.reset();
        server.expect(requestTo("https://api.deepseek.com/chat/completions"))
                .andRespond(withSuccess("data: {\"choices\":[{\"delta\":{\"content\":\"半截\"}}]}\n\n", MediaType.TEXT_EVENT_STREAM));
        assertThrows(Exception.class, () -> client.stream(List.of(), value -> {}));
        server.verify();
    }

    /** 字节拆包覆盖中文、跨缓冲区及 CRLF，首帧必须在读取后续帧前交付。 */
    @Test
    void clientDeliversFragmentedUtf8BeforeReadingNextFrame() throws Exception {
        String content = "你好🌿".repeat(900);
        String first = "data: {\"choices\":[{\"delta\":{\"content\":\"" + content + "\"}}]}\r\n\r\n";
        byte[] bytes = (first + "data: [DONE]").getBytes(StandardCharsets.UTF_8);
        int boundary = first.getBytes(StandardCharsets.UTF_8).length;
        var delivered = new AtomicBoolean();
        var closed = new AtomicBoolean();
        var input = new ByteArrayInputStream(bytes) {
            /** 每次最多返回七字节，并禁止在首帧交付前读取下一帧。 */
            @Override
            public synchronized int read(byte[] buffer, int offset, int length) {
                if (pos >= boundary) assertTrue(delivered.get(), "首帧应立即交付");
                int limit = pos < boundary ? boundary - pos : count - pos;
                return super.read(buffer, offset, Math.min(length, Math.min(7, Math.max(1, limit))));
            }

            /** 模拟网络暂时没有更多字节，避免内存流提前读完整个响应。 */
            @Override
            public synchronized int available() { return 0; }

            /** 确认完成时关闭上游响应。 */
            @Override
            public void close() { closed.set(true); }
        };
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var client = new DeepSeekClient(new AiProperties(), new ObjectMapper());
        ReflectionTestUtils.setField(client, "client", builder.build());
        server.expect(requestTo("https://api.deepseek.com/chat/completions"))
                .andRespond(withSuccess(new InputStreamResource(input), MediaType.TEXT_EVENT_STREAM));
        client.stream(List.of(), value -> {
            assertEquals(content, value);
            assertFalse(delivered.getAndSet(true));
        });
        assertTrue(delivered.get());
        assertTrue(closed.get());
        server.verify();
    }

    /** 分块读取仍限制单行与多行 data 累计大小，防止异常上游占用内存。 */
    @Test
    void clientRejectsOversizedLinesAndFrames() {
        for (String body : List.of(":".repeat(65537),
                "data:" + "x".repeat(40000) + "\ndata:" + "x".repeat(40000) + "\n\n")) {
            var builder = RestClient.builder();
            var server = MockRestServiceServer.bindTo(builder).build();
            var client = new DeepSeekClient(new AiProperties(), new ObjectMapper());
            ReflectionTestUtils.setField(client, "client", builder.build());
            server.expect(requestTo("https://api.deepseek.com/chat/completions"))
                    .andRespond(withSuccess(body, MediaType.TEXT_EVENT_STREAM));
            var exception = assertThrows(Exception.class, () -> client.stream(List.of(), value -> {}));
            assertTrue(exception.getMessage().contains("上游分片过大"));
            server.verify();
        }
    }

    /** 字面匹配包含大小写、正则符号及跨分片词，尾部安全文本最终放行。 */
    @Test
    void filterHoldsCrossChunkWordsAndSurrogates() throws Exception {
        var properties = new AiProperties();
        properties.setSensitiveWords(List.of("敏感样例", "AbC+"));
        var filter = new SensitiveWordFilter(properties);
        assertTrue(filter.blocked("包含abc+内容"));
        assertFalse(filter.blocked("ABC"));
        var buffer = filter.buffer();
        String prefix = buffer.append("正常。敏感");
        assertFalse(prefix.contains("敏感"));
        assertThrows(SensitiveWordFilter.BlockedOutput.class, () -> buffer.append("样例"));
        var safe = filter.buffer();
        String result = safe.append("你好🌿，") + safe.append("今天不错") + safe.finish();
        assertEquals("你好🌿，今天不错", result);
        assertEquals(result, safe.content());
    }
}
