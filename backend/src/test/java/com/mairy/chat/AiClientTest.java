package com.mairy.chat;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mairy.chat.config.AiProperties;
import com.mairy.chat.service.ai.DeepSeekClient;
import com.mairy.chat.service.ai.SensitiveWordFilter;
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
