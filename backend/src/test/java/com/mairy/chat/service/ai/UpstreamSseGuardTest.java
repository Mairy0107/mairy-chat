package com.mairy.chat.service.ai;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;

/** 原始协议边界独立于 SDK 映射，使用真实字段且不输出原始正文。 */
class UpstreamSseGuardTest {
    /** 构造带 id、index 和模型信息的真实增量帧。 */
    static String frame(String delta, String finish) {
        return "data: {\"id\":\"fixture-1\",\"object\":\"chat.completion.chunk\",\"created\":1,"
                + "\"model\":\"deepseek-chat\",\"choices\":[{\"index\":0,\"delta\":" + delta
                + ",\"finish_reason\":" + finish + "}]}\n\n";
    }

    /** 每字节拆包仍保留 UTF-8、CRLF、注释、多行 data 和无末尾换行 DONE。 */
    @Test
    void byteSplitsDoNotMoveReadPosition() {
        var guard = new UpstreamSseGuard(new ObjectMapper());
        String input = ": heartbeat\r\n\r\n" + frame("{\"content\":\"你好🌿\"}", "null")
                .replace("\n", "\r\n").replace(",\"choices\"", ",\r\ndata: \"choices\"") + "data: [DONE]";
        for (byte value : input.getBytes(StandardCharsets.UTF_8)) {
            var buffer = DefaultDataBufferFactory.sharedInstance.wrap(new byte[] {value});
            guard.observe(buffer);
            assertEquals(0, buffer.readPosition());
            assertEquals(1, buffer.readableByteCount());
        }
        guard.end();
        guard.requireComplete();
    }

    /** SDK 在 DONE 时取消也能证明完整，无须等待 EOF。 */
    @Test
    void doneProofSurvivesSdkCancellation() {
        var guard = new UpstreamSseGuard(new ObjectMapper());
        observe(guard, frame("{\"content\":\"正文\"}", "\"length\"") + "data: [DONE]\n\n");
        guard.requireComplete();
    }

    /** EOF、半行与伪造标记不能把已输出的部分正文变为成功。 */
    @ParameterizedTest
    @ValueSource(strings = {"", "data: [DON", "data: [DONE]x", ": [DONE]", "data: null"})
    void truncatedAfterTextFails(String tail) {
        var guard = new UpstreamSseGuard(new ObjectMapper());
        assertThrows(RuntimeException.class, () -> {
            observe(guard, frame("{\"content\":\"半截正文\"}", "null") + tail);
            guard.end();
            guard.requireComplete();
        });
    }

    /** JSON、choices、工具与字段类型异常均不得进入 SDK 的宽松映射。 */
    @ParameterizedTest
    @ValueSource(strings = {"{", "null", "{\"error\":{\"message\":\"fixture secret\"}}",
            "{\"id\":\"x\",\"choices\":null}", "{\"id\":\"x\",\"choices\":[]}",
            "{\"id\":\"x\",\"choices\":[null]}",
            "{\"id\":\"x\",\"choices\":[{\"index\":0,\"delta\":null}]}",
            "{\"id\":\"x\",\"choices\":[{\"index\":0,\"delta\":{\"content\":42}}]}"})
    void invalidPayloadFailsSafely(String payload) {
        var guard = new UpstreamSseGuard(new ObjectMapper());
        var error = assertThrows(RuntimeException.class, () -> observe(guard, "data: " + payload + "\n\n"));
        assertEquals("上游流校验失败", error.getMessage());
        assertNull(error.getCause());
    }

    /** 合法 JSON 后的额外值必须拒绝，应用共享的解析配置保持独立。 */
    @ParameterizedTest
    @ValueSource(strings = {"false", "{}", "fixture-secret"})
    void trailingJsonFailsSafely(String tail) {
        var mapper = new ObjectMapper();
        var guard = new UpstreamSseGuard(mapper);
        String input = frame("{\"content\":\"正文\"}", "null").replace("\n\n", " " + tail + "\n\n");
        var error = assertThrows(IllegalStateException.class, () -> observe(guard, input));
        assertEquals("上游流校验失败", error.getMessage());
        assertNull(error.getCause());
        assertFalse(mapper.isEnabled(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS));
    }

    /** 超长行、多行累计、UTF-8 残片及非法码点必须有界失败。 */
    @Test
    void boundedProtocolAndStrictUtf8() {
        for (String input : Arrays.asList(":".repeat(65537),
                "data: " + "x".repeat(40000) + "\ndata: " + "x".repeat(40000) + "\n\n",
                frame("{\"content\":\"" + "x".repeat(16001) + "\"}", "null"))) {
            assertThrows(RuntimeException.class, () -> observe(new UpstreamSseGuard(new ObjectMapper()), input));
        }
        for (byte[] input : new byte[][] {{(byte) 0xf0}, {(byte) 0xc0, (byte) 0x80},
                {(byte) 0xed, (byte) 0xa0, (byte) 0x80}}) {
            var guard = new UpstreamSseGuard(new ObjectMapper());
            assertThrows(RuntimeException.class, () -> {
                guard.observe(DefaultDataBufferFactory.sharedInstance.wrap(input));
                guard.end();
            });
        }
    }

    /** 旁路观察单个缓冲，不改变传递给 SDK 的数据。 */
    private static void observe(UpstreamSseGuard guard, String input) {
        guard.observe(DefaultDataBufferFactory.sharedInstance.wrap(input.getBytes(StandardCharsets.UTF_8)));
    }
}
