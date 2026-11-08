package com.mairy.chat.service.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.ByteBuffer;
import org.springframework.core.io.buffer.DataBuffer;

/** 请求独享的旁路校验，不移动 DataBuffer 游标，不替代官方模型的协议映射。 */
final class UpstreamSseGuard {
    private static final int FRAME_LIMIT = 65536;
    private final ObjectMapper json;
    private final StringBuilder line = new StringBuilder();
    private final StringBuilder data = new StringBuilder();
    private int remaining;
    private int codePoint;
    private int minimum;
    private boolean cr;
    private int frames;
    private int totalChars;
    private int textChars;
    private volatile boolean done;
    private volatile RuntimeException failure;

    /** 每轮新建校验器，JSON 解析器复用应用现有实例。 */
    UpstreamSseGuard(ObjectMapper json) { this.json = json; }

    /** 复制字节视图逐字节检查 UTF-8，原缓冲仍由 WebClient 单次消费与释放。 */
    void observe(DataBuffer buffer) {
        try (var buffers = buffer.readableByteBuffers()) {
            while (buffers.hasNext()) {
                ByteBuffer bytes = buffers.next().duplicate();
                while (bytes.hasRemaining()) acceptByte(bytes.get() & 0xff);
            }
        } catch (RuntimeException exception) {
            failure = exception;
            throw exception;
        }
    }

    /** 正常 EOF 处理半行与无换行 DONE；取消路径仍由已观察的 DONE 判定完整性。 */
    void end() {
        try {
            if (remaining != 0) throw invalid();
            if (!line.isEmpty()) consumeLine();
            consumeData();
        } catch (RuntimeException exception) {
            failure = exception;
            throw exception;
        }
    }

    /** SDK 正常完成不代表完整，必须实际看到上游 DONE 且无协议错误。 */
    void requireComplete() {
        checkFailure();
        if (!done) throw invalid();
    }

    /** 跨 Reactor 异常包装恢复固定错误分类，保留内容拦截语义。 */
    void checkFailure() {
        if (failure != null) throw failure;
    }

    /** 严格增量解码 UTF-8，支持中文和 emoji 在任意字节处分片。 */
    private void acceptByte(int value) {
        if (remaining == 0) {
            if (value < 0x80) { acceptChar((char) value); return; }
            if (value >= 0xc2 && value <= 0xdf) { remaining = 1; codePoint = value & 0x1f; minimum = 0x80; }
            else if (value >= 0xe0 && value <= 0xef) { remaining = 2; codePoint = value & 0xf; minimum = 0x800; }
            else if (value >= 0xf0 && value <= 0xf4) { remaining = 3; codePoint = value & 7; minimum = 0x10000; }
            else throw invalid();
        } else {
            if ((value & 0xc0) != 0x80) throw invalid();
            codePoint = (codePoint << 6) | (value & 0x3f);
            if (--remaining == 0) {
                if (codePoint < minimum || codePoint > 0x10ffff || (codePoint >= 0xd800 && codePoint <= 0xdfff)) throw invalid();
                for (char valueChar : Character.toChars(codePoint)) acceptChar(valueChar);
            }
        }
    }

    /** CR、LF 和 CRLF 均是行边界；限制单行及整轮协议体防止 SDK 预取耗尽内存。 */
    private void acceptChar(char value) {
        if (++totalChars > 1048576) throw invalid();
        if (value == '\n' && cr) { cr = false; return; }
        cr = value == '\r';
        if (value == '\r' || value == '\n') consumeLine();
        else {
            line.append(value);
            if (line.length() > FRAME_LIMIT) throw invalid();
        }
    }

    /** 按 SSE 规范合并多行 data，注释不进入正文。 */
    private void consumeLine() {
        String value = line.toString();
        line.setLength(0);
        if (value.isEmpty()) { consumeData(); return; }
        if (value.startsWith("data:")) {
            String part = value.substring(5);
            if (part.startsWith(" ")) part = part.substring(1);
            if (!data.isEmpty()) data.append('\n');
            data.append(part);
            if (data.length() > FRAME_LIMIT) throw invalid();
        }
    }

    /** 仅校验结构、完成标记和危险结束原因，普通文本仍由官方模型解码。 */
    private void consumeData() {
        if (data.isEmpty()) return;
        String value = data.toString();
        data.setLength(0);
        if (done) throw invalid();
        if ("[DONE]".equals(value)) { done = true; return; }
        if (++frames > 4096) throw invalid();
        JsonNode payload;
        try { payload = json.readTree(value); }
        catch (Exception ignored) { throw invalid(); }
        if (payload == null || !payload.isObject() || payload.has("error")
                || !payload.path("id").isTextual() || payload.path("id").asText().isBlank()) throw invalid();
        JsonNode choices = payload.path("choices");
        if (!choices.isArray() || choices.size() != 1) throw invalid();
        JsonNode choice = choices.get(0);
        JsonNode delta = choice.path("delta");
        if (!choice.path("index").isIntegralNumber() || choice.path("index").asInt() != 0 || !delta.isObject()) throw invalid();
        if (delta.hasNonNull("tool_calls") && !delta.path("tool_calls").isEmpty()) throw invalid();
        if (delta.hasNonNull("function_call")) throw invalid();
        if (delta.hasNonNull("role") && !"assistant".equals(delta.path("role").asText())) throw invalid();
        JsonNode content = delta.path("content");
        if (!content.isMissingNode() && !content.isNull()) {
            if (!content.isTextual()) throw invalid();
            textChars += content.textValue().length();
            if (textChars > 16000) throw invalid();
        }
        JsonNode finish = choice.path("finish_reason");
        if (!finish.isMissingNode() && !finish.isNull()) {
            if ("content_filter".equals(finish.asText())) throw new SensitiveWordFilter.BlockedOutput();
            if (!"stop".equals(finish.asText()) && !"length".equals(finish.asText())) throw invalid();
        }
    }

    /** 错误仅保留固定分类，异常链不携带上游正文。 */
    private static IllegalStateException invalid() { return new IllegalStateException("上游流校验失败"); }
}
