package com.mairy.chat.service.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mairy.chat.dto.AiViews;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.Writer;
import java.util.Map;
import java.util.concurrent.Semaphore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** 同步 MVC SSE 避免改动鉴权和 WS；最多八条模型流，不占用数据库长事务。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiStreamService {
    private final AiService ai;
    private final DeepSeekClient client;
    private final SensitiveWordFilter filter;
    private final ObjectMapper json;
    private final Semaphore slots = new Semaphore(8);

    /** 所有业务门禁在 SSE 响应提交前完成，429等错误仍沿用 JSON 响应。 */
    public void send(Long threadId, String content, HttpServletResponse response) throws IOException {
        if (!slots.tryAcquire()) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "助手忙碌，请稍后再试");
        try {
            AiService.Turn turn = ai.prepare(threadId, content.strip());
            response.setContentType("text/event-stream");
            response.setCharacterEncoding("UTF-8");
            response.setHeader("Cache-Control", "no-cache, no-transform");
            response.setHeader("X-Accel-Buffering", "no");
            stream(turn, response.getWriter());
        } finally {
            slots.release();
        }
    }

    /** 每个增量先过滤，异常和命中均替换展示内容，最终只保存安全文本。 */
    private void stream(AiService.Turn turn, Writer writer) {
        var buffer = filter.buffer();
        String finalText;
        String outcome = "completed";
        boolean connected = true;
        try {
            event(writer, "user", turn.user());
            client.stream(turn.context(), delta -> {
                String ready = buffer.append(delta);
                if (!ready.isEmpty()) event(writer, "delta", Map.of("content", ready));
            });
            String tail = buffer.finish();
            if (!tail.isEmpty()) event(writer, "delta", Map.of("content", tail));
            finalText = buffer.content();
            if (finalText.isBlank()) throw new IOException("空回复");
        } catch (SensitiveWordFilter.BlockedOutput exception) {
            log.info("ai_audit type=output_blocked");
            finalText = SensitiveWordFilter.REFUSAL;
            outcome = "blocked";
        } catch (Exception exception) {
            // 不记录异常对象，第三方异常可能包含请求地址、正文或凭证。
            log.info("ai_audit type=stream_failed");
            finalText = "回复已中断，请稍后重新提问。";
            outcome = "failed";
        }
        if (!"completed".equals(outcome)) {
            try { event(writer, "replace", Map.of("content", finalText)); }
            catch (IOException exception) { connected = false; }
        }
        try {
            AiViews.MessageView message = ai.finish(turn, finalText);
            if (connected) event(writer, "done", new AiViews.Done(message, ai.remaining(turn.userId()), outcome));
        } catch (Exception exception) {
            log.info("ai_audit type=save_or_delivery_failed");
            try { ai.release(turn); } catch (Exception ignored) { log.info("ai_audit type=release_failed"); }
            try { event(writer, "error", Map.of("message", "回复确认失败，请刷新对话")); }
            catch (IOException ignored) { /* 断连后不重试传输，也不记录正文。 */ }
        }
    }

    /** JSON 单行封装 SSE，换行正文不会破坏事件边界。 */
    private void event(Writer writer, String type, Object data) throws IOException {
        writer.write("event: " + type + "\ndata: " + json.writeValueAsString(data) + "\n\n");
        writer.flush();
        // Servlet 的 PrintWriter 会吞掉 IOException，需要显式检查断连。
        if (writer instanceof java.io.PrintWriter print && print.checkError()) throw new IOException("客户端断开");
    }
}
